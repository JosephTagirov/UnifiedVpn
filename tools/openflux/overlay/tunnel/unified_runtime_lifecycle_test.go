// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import (
	"context"
	"errors"
	"net"
	"sync"
	"syscall"
	"testing"
	"time"
)

const unifiedLifecycleDestination = "192.0.2.1:443"

func newUnifiedLifecyclePair(t *testing.T, dial func(context.Context, string) (net.Conn, error)) (*UnifiedTCPTunnel, func()) {
	t.Helper()
	clientBase, serverBase := newTestTransportPair(t)
	client, err := NewUnifiedTCPTunnel(clientBase, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = clientBase.Stop()
		client.UnifiedClose()
	})
	server, err := NewUnifiedTCPTunnel(serverBase, true)
	if err != nil {
		t.Fatal(err)
	}
	stopServer := sync.OnceFunc(func() {
		_ = serverBase.Stop()
		server.UnifiedClose()
	})
	t.Cleanup(stopServer)
	server.unifiedExitForwarder(dial)
	if err := serverBase.Start(); err != nil {
		t.Fatal(err)
	}
	if err := clientBase.Start(); err != nil {
		t.Fatal(err)
	}
	return client, stopServer
}

func TestUnifiedTunnelRefusedDestinationDoesNotAcceptClient(t *testing.T) {
	dialed := make(chan string, 1)
	client, _ := newUnifiedLifecyclePair(t, func(_ context.Context, address string) (net.Conn, error) {
		select {
		case dialed <- address:
		default:
		}
		return nil, &net.OpError{Op: "dial", Net: "tcp", Err: syscall.ECONNREFUSED}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := client.UnifiedDialContext(ctx, unifiedLifecycleDestination)
	if conn != nil {
		conn.Close()
	}
	if err == nil {
		t.Fatal("tunnel accepted a connection before the destination dial succeeded")
	}
	var timeout net.Error
	if ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &timeout) && timeout.Timeout()) {
		t.Fatal("destination refusal was not propagated before the dial timeout")
	}
	select {
	case address := <-dialed:
		if address != unifiedLifecycleDestination {
			t.Fatal("forwarder changed the synthetic destination")
		}
	default:
		t.Fatal("connection failed without attempting the injected destination dial")
	}
}

// Track full Close separately: a peer observing EOF proves only CloseWrite.
type unifiedLifecycleConn struct {
	net.Conn
	closed      chan struct{}
	readStarted chan struct{}
	readEnded   chan struct{}
	closeOnce   sync.Once
	readOnce    sync.Once
	endOnce     sync.Once
}

func (c *unifiedLifecycleConn) Read(data []byte) (int, error) {
	c.readOnce.Do(func() { close(c.readStarted) })
	n, err := c.Conn.Read(data)
	if err != nil {
		c.endOnce.Do(func() { close(c.readEnded) })
	}
	return n, err
}

func (c *unifiedLifecycleConn) Close() error {
	err := c.Conn.Close()
	c.closeOnce.Do(func() { close(c.closed) })
	return err
}

func (c *unifiedLifecycleConn) CloseWrite() error {
	if writer, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return writer.CloseWrite()
	}
	return errors.New("synthetic socket does not support half-close")
}

func TestUnifiedTunnelCloseReleasesEstablishedIdleRemote(t *testing.T) {
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	if err := listener.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var owned []*unifiedLifecycleConn
	t.Cleanup(func() {
		mu.Lock()
		connections := append([]*unifiedLifecycleConn(nil), owned...)
		mu.Unlock()
		for _, conn := range connections {
			conn.Close()
		}
	})
	dialed := make(chan *unifiedLifecycleConn, 1)
	client, stopServer := newUnifiedLifecyclePair(t, func(ctx context.Context, address string) (net.Conn, error) {
		if address != unifiedLifecycleDestination {
			return nil, errors.New("unexpected synthetic destination")
		}
		dialer := net.Dialer{Timeout: time.Second}
		conn, err := dialer.DialContext(ctx, "tcp4", listener.Addr().String())
		if err != nil {
			return nil, err
		}
		tracked := &unifiedLifecycleConn{
			Conn: conn, closed: make(chan struct{}),
			readStarted: make(chan struct{}), readEnded: make(chan struct{}),
		}
		mu.Lock()
		owned = append(owned, tracked)
		mu.Unlock()
		select {
		case dialed <- tracked:
			return tracked, nil
		default:
			tracked.Close()
			return nil, errors.New("duplicate synthetic destination dial")
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := client.UnifiedDialContext(ctx, unifiedLifecycleDestination)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	peer, err := listener.AcceptTCP()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { peer.Close() })
	var remote *unifiedLifecycleConn
	select {
	case remote = <-dialed:
	case <-ctx.Done():
		t.Fatal("destination socket was not recorded")
	}
	select {
	case <-remote.readStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("idle remote copy did not start")
	}
	select {
	case <-remote.closed:
		t.Fatal("idle remote closed before tunnel shutdown")
	default:
	}
	stopServer()
	select {
	case <-remote.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("UnifiedClose left the established remote socket open")
	}
	select {
	case <-remote.readEnded:
	case <-time.After(2 * time.Second):
		t.Fatal("UnifiedClose left the idle remote read blocked")
	}
}

func TestUnifiedTunnelCloseCancelsPendingDestinationDial(t *testing.T) {
	started := make(chan struct{}, 1)
	cancelled := make(chan error, 1)
	abort := make(chan struct{})
	client, stopServer := newUnifiedLifecyclePair(t, func(ctx context.Context, address string) (net.Conn, error) {
		if address != unifiedLifecycleDestination {
			return nil, errors.New("unexpected synthetic destination")
		}
		started <- struct{}{}
		select {
		case <-ctx.Done():
			cancelled <- ctx.Err()
			return nil, ctx.Err()
		case <-abort:
			return nil, errors.New("synthetic dial cleanup")
		}
	})
	t.Cleanup(func() { close(abort) })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result := make(chan error, 1)
	go func() {
		conn, err := client.UnifiedDialContext(ctx, unifiedLifecycleDestination)
		if conn != nil {
			conn.Close()
		}
		result <- err
	}()
	select {
	case <-started:
	case <-ctx.Done():
		t.Fatal("injected destination dial did not start")
	}
	stopServer()
	select {
	case err := <-cancelled:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("destination dial did not receive shutdown cancellation")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("UnifiedClose did not cancel the pending destination dial")
	}
	cancel()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("pending destination dial unexpectedly accepted a client")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("client dial did not return after cancellation")
	}
}
