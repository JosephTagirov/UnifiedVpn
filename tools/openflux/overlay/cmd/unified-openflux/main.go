// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	socks "github.com/things-go/go-socks5"
	_ "github.com/wlynxg/anet"
	"openflux/transport"
	"openflux/transport/yandex"
	"openflux/tunnel"
)

func main() {
	path := flag.String("config", "", "Private JSON configuration file")
	version := flag.Bool("version", false, "Print pinned build provenance")
	check := flag.Bool("check-config", false, "Validate configuration without networking")
	checkDocument := flag.Bool("check-document", false, "Check HTTPS document bootstrap without joining its WebSocket room")
	bootstrap := flag.Bool("bootstrap-stdio", false, "Use inherited pipes for anonymous browser verification")
	flag.Parse()
	if *version {
		fmt.Println(versionText)
		return
	}
	if flag.NArg() != 0 || *path == "" {
		fatal(errors.New("use --config with a private configuration file"))
	}
	if *check && *checkDocument {
		fatal(errors.New("choose either offline configuration validation or document bootstrap diagnostic"))
	}
	c, err := readConfiguration(*path)
	if err != nil {
		fatal(err)
	}
	if *check {
		fmt.Println("OPENFLUX_CONFIG_OK")
		return
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	var browser *stdioBrowserBootstrap
	var provider yandex.BrowserCookieProvider
	if *bootstrap {
		browser = newStdioBrowserBootstrap(os.Stdin, os.Stdout)
		provider = browser.Cookies
	}
	if *checkDocument {
		diagnosticCtx, stop := context.WithTimeout(ctx, 90*time.Second)
		base := newDocumentTransport(c)
		err = base.ConfigureBrowserBootstrap(diagnosticCtx, provider)
		if err == nil {
			err = base.CheckDocumentAccess()
		}
		base.Stop()
		stop()
		if err == nil {
			fmt.Println("OPENFLUX_DOCUMENT_OK")
		}
	} else {
		err = runWithBootstrap(ctx, c, provider)
	}
	if browser != nil {
		browser.Close()
	}
	if err != nil {
		fatal(err)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "OPENFLUX_FATAL:", err)
	os.Exit(1)
}

type tunnelResolver struct{ resolver *net.Resolver }

func (r tunnelResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	lookupCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	ips, err := r.resolver.LookupIP(lookupCtx, "ip4", name)
	if err != nil || len(ips) == 0 {
		return ctx, nil, errors.New("tunnel DNS resolution failed")
	}
	// The resolver's short-lived context must not cancel the TCP stream.
	return ctx, ips[0], nil
}

func run(ctx context.Context, c configuration) error {
	return runWithBootstrap(ctx, c, nil)
}

type documentTransport interface {
	transport.Transport
	ConfigureBrowserBootstrap(context.Context, yandex.BrowserCookieProvider) error
	BrowserErrors() <-chan error
	CheckDocumentAccess() error
}

func newDocumentTransport(c configuration) documentTransport {
	if c.Transport == "vyandex" {
		base := yandex.NewYandexVolgaTransport(c.DocumentURL, transport.DefaultConfig())
		if c.Mode == "server" {
			base.EnableServerRetries()
		}
		return base
	}
	return yandex.NewYandexDocsTransport(c.DocumentURL, transport.DefaultConfig())
}

func runWithBootstrap(ctx context.Context, c configuration, provider yandex.BrowserCookieProvider) error {
	ctx, cancelRun := context.WithCancel(ctx)
	defer cancelRun()
	serverMode := c.Mode == "server"
	if serverMode && runtime.GOOS != "linux" {
		return errors.New("the OpenFlux server requires isolated Linux networking")
	}
	base := newDocumentTransport(c)
	if err := base.ConfigureBrowserBootstrap(ctx, provider); err != nil {
		return err
	}
	encrypted, err := transport.NewEncryptedTransport(base, c.EncryptionKey, c.DocumentURL, serverMode)
	if err != nil {
		return errors.New("cannot initialize mandatory AES-256-GCM encryption")
	}
	session := newPeerSession(transport.NewCompressedTransport(encrypted), serverMode)
	network, err := tunnel.NewUnifiedTCPTunnel(session, serverMode)
	if err != nil {
		return errors.New("cannot initialize OpenFlux network stack")
	}
	defer network.UnifiedClose()
	if err := session.Start(); err != nil {
		// Volga authenticates synchronously; always cancel bootstrap on failed startup.
		_ = session.Stop()
		return err
	}
	defer session.Stop()
	fmt.Println("OPENFLUX_ENCRYPTION AES-256-GCM")
	if serverMode {
		fmt.Println("OPENFLUX_SERVER_WAITING")
		for {
			select {
			case <-ctx.Done():
				return nil
			case err := <-base.BrowserErrors():
				if errors.Is(err, yandex.ErrVolgaSession) {
					return err
				}
				// Keep the native server alive while the transport applies its
				// retry backoff; do not create a container restart storm.
				fmt.Println("OPENFLUX_BROWSER_UNAVAILABLE")
			}
		}
	}
	timeout := time.Duration(c.HandshakeTimeoutSeconds) * time.Second
	initialTimeout := timeout
	if provider != nil {
		initialTimeout += 50 * time.Second
	}
	handshakeCtx, cancel := context.WithTimeout(ctx, initialTimeout)
	err = waitPeerOrBrowserError(handshakeCtx, session.waitPeer, base.BrowserErrors())
	cancel()
	if err != nil {
		return err
	}
	resolver := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return network.UnifiedDialContext(ctx, c.DNSServer)
	}}
	options := []socks.Option{
		socks.WithResolver(tunnelResolver{resolver}),
		socks.WithRule(&socks.PermitCommand{EnableConnect: true}),
		socks.WithDial(func(ctx context.Context, kind, addr string) (net.Conn, error) {
			if kind != "tcp" {
				return nil, errors.New("OpenFlux supports TCP only")
			}
			ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
			defer cancel()
			return network.UnifiedDialContext(ctx, addr)
		}),
	}
	if c.SOCKSUsername != "" {
		options = append(options, socks.WithCredential(socks.StaticCredentials{c.SOCKSUsername: c.SOCKSPassword}))
	}
	socksServer := socks.NewServer(options...)
	listener, err := net.Listen("tcp", c.SOCKS5)
	if err != nil {
		return errors.New("cannot bind the configured loopback SOCKS port")
	}
	defer listener.Close()
	fmt.Println("OPENFLUX_READY")
	errorsCh := make(chan error, 2)
	go func() {
		if err := socksServer.Serve(listener); err != nil {
			errorsCh <- errors.New("local SOCKS listener stopped")
		}
	}()
	go func() {
		ticker := time.NewTicker(20 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				probeCtx, cancel := context.WithTimeout(ctx, timeout)
				err := session.waitPeer(probeCtx)
				cancel()
				if err != nil {
					errorsCh <- err
					return
				}
			}
		}
	}()
	select {
	case <-ctx.Done():
		return nil
	case err := <-errorsCh:
		return err
	case err := <-base.BrowserErrors():
		return err
	}
}

func waitPeerOrBrowserError(ctx context.Context, wait func(context.Context) error, browserErrors <-chan error) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	result := make(chan error, 1)
	go func() { result <- wait(ctx) }()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case err := <-result:
		return err
	case err := <-browserErrors:
		return err
	}
}
