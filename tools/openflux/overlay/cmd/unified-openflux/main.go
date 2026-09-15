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
	"universal-bypass-tool/transport"
	"universal-bypass-tool/transport/yandex"
	"universal-bypass-tool/tunnel"
)

func main() {
	path := flag.String("config", "", "Private JSON configuration file")
	version := flag.Bool("version", false, "Print pinned build provenance")
	check := flag.Bool("check-config", false, "Validate configuration without networking")
	flag.Parse()
	if *version {
		fmt.Println(versionText)
		return
	}
	if flag.NArg() != 0 || *path == "" {
		fatal(errors.New("use --config with a private configuration file"))
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
	if err := run(ctx, c); err != nil {
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
	ctx, cancelRun := context.WithCancel(ctx)
	defer cancelRun()
	serverMode := c.Mode == "server"
	if serverMode && runtime.GOOS != "linux" {
		return errors.New("the OpenFlux server requires isolated Linux networking")
	}
	base := yandex.NewYandexDocsTransport(c.DocumentURL, transport.DefaultConfig())
	encrypted, err := transport.NewEncryptedTransport(base, c.EncryptionKey, c.DocumentURL, serverMode)
	if err != nil {
		return errors.New("cannot initialize mandatory AES-256-GCM encryption")
	}
	session := newPeerSession(transport.NewCompressedTransport(encrypted), serverMode)
	network := tunnel.NewTCPTunnel(session, serverMode)
	defer network.UnifiedClose()
	if network.UnifiedReady() != nil {
		return errors.New("cannot initialize OpenFlux network stack")
	}
	if session.Start() != nil {
		return errors.New("cannot initialize Yandex Docs transport")
	}
	defer session.Stop()
	fmt.Println("OPENFLUX_ENCRYPTION AES-256-GCM")
	if serverMode {
		fmt.Println("OPENFLUX_SERVER_WAITING")
		<-ctx.Done()
		return nil
	}
	timeout := time.Duration(c.HandshakeTimeoutSeconds) * time.Second
	handshakeCtx, cancel := context.WithTimeout(ctx, timeout)
	err = session.waitPeer(handshakeCtx)
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
	}
}
