// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
    "net/url"
	"sync"
	"syscall"
	"time"

	"github.com/p1neappleXpress/OpenFlux/socks5"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/registry"
	"github.com/p1neappleXpress/OpenFlux/tunnel"
	"github.com/p1neappleXpress/OpenFlux/utils"
	_ "github.com/wlynxg/anet"
)

func main() {
	path := flag.String("config", "", "Private JSON configuration")
	version := flag.Bool("version", false, "Build provenance")
	check := flag.Bool("check-config", false, "Offline configuration validation")
	control := flag.Bool("control-stdio", false, "Stop on inherited stdin STOP or EOF")
	flag.Parse()
	if *version {
		fmt.Println(versionText)
		return
	}
	if *path == "" || flag.NArg() != 0 {
		fail(errors.New("private configuration required"))
	}
	c, err := readConfiguration(*path)
	if err != nil {
		fail(err)
	}
	if *check {
		fmt.Println("OPENFLUX_CONFIG_OK")
		return
	}
	// Carrier diagnostics may contain conference URLs and tokens. The app receives
	// only the fixed lifecycle markers below, never arbitrary upstream log output.
	log.SetOutput(io.Discard)
	utils.SetLevel(0)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if *control {
		go stopOnInput(os.Stdin, cancel)
	}
	if err := run(ctx, c); err != nil && ctx.Err() == nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "OPENFLUX_FATAL:", err)
	os.Exit(1)
}

func stopOnInput(input io.Reader, cancel context.CancelFunc) {
	r := bufio.NewReader(io.LimitReader(input, 16))
	line, err := r.ReadString('\n')
	if line == "STOP\n" || line == "STOP\r\n" || err != nil {
		cancel()
	}
}

func run(ctx context.Context, c configuration) error {
	if err := c.validate(); err != nil {
		return err
	}
	session, err := transport.NewSession(transport.PeerParameters{
		Capabilities:  transport.CapabilityIPv4 | transport.CapabilityTCP | transport.CapabilityUDP | transport.CapabilityICMPErrors,
		MaxPacketSize: 65000,
	}, false)
	if err != nil {
		return errors.New("Session initialization failed")
	}
	var params map[string]interface{}
    if c.Transport == "direct" {
        endpoint, _ := url.Parse(c.DocumentURL)
        params = map[string]interface{}{"dial": endpoint.Host}
    }
    carrier, err := registry.New(c.Transport, c.DocumentURL, params, registry.Options{Base: transport.DefaultConfig(), StrictDirect: true})
	if err != nil {
		return errors.New("Session carrier initialization failed")
	}
	if err = session.AddTransport(c.Transport, carrier, c.EncryptionKey, c.DocumentURL, 50); err != nil {
		return errors.New("Session encryption initialization failed")
	}
	var stopOnce sync.Once
	stop := func() { stopOnce.Do(func() { _ = session.Stop() }) }
	defer stop()
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			stop()
		case <-done:
		}
	}()
	fmt.Println("OPENFLUX_SESSION_STARTING")
	if session.Start() != nil {
		return errors.New("Session handshake failed; retry the connection")
	}
	if _, ready := session.PeerParameters(); !ready {
		return errors.New("Session peer was not authenticated")
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	network := tunnel.NewTCPTunnelMode(session, false, tunnel.ExitModeL4)
	defer network.Close()
	server := socks5.NewSOCKS5Server(c.SOCKS5, network)
	server.SetAuth(c.SOCKSUsername, c.SOCKSPassword)
	if server.Bind() != nil {
		return errors.New("cannot bind the loopback SOCKS port")
	}
	defer server.Close()
	errorsCh := make(chan error, 1)
	go func() { errorsCh <- server.Start() }()
	fmt.Println("OPENFLUX_ENCRYPTION AES-256-GCM Session")
	fmt.Println("OPENFLUX_READY")
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	var unavailableSince time.Time
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-errorsCh:
			return errors.New("Session SOCKS listener stopped")
		case now := <-tick.C:
			if session.IsConnected() {
				unavailableSince = time.Time{}
				continue
			}
			if unavailableSince.IsZero() {
				unavailableSince = now
			}
			if now.Sub(unavailableSince) >= 60*time.Second {
				return errors.New("Session peer is unavailable; retry the connection")
			}
		}
	}
}
