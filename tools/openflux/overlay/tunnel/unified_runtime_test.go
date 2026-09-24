// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"openflux/transport"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

type queuedTestTransport struct {
	*transport.BaseTransport
	peer  *queuedTestTransport
	inbox chan []byte
	done  chan struct{}
	once  sync.Once
}

func newTestTransportPair(t *testing.T) (*queuedTestTransport, *queuedTestTransport) {
	t.Helper()
	makeOne := func() *queuedTestTransport {
		return &queuedTestTransport{
			BaseTransport: transport.NewBaseTransport(transport.DefaultConfig()),
			inbox:         make(chan []byte, 256), done: make(chan struct{}),
		}
	}
	a, b := makeOne(), makeOne()
	a.peer, b.peer = b, a
	t.Cleanup(func() { _ = a.Stop(); _ = b.Stop() })
	return a, b
}

func (q *queuedTestTransport) Start() error {
	if err := q.BaseTransport.Start(); err != nil {
		return err
	}
	q.SetConnected(true)
	go func() {
		for {
			select {
			case <-q.done:
				return
			case data := <-q.inbox:
				q.CallReceive(data)
			}
		}
	}()
	return nil
}

func (q *queuedTestTransport) Stop() error {
	q.once.Do(func() { close(q.done) })
	return q.BaseTransport.Stop()
}

func (q *queuedTestTransport) Send(data []byte) error {
	select {
	case <-q.done:
		return errors.New("test transport stopped")
	case <-q.peer.done:
		return errors.New("test peer stopped")
	case q.peer.inbox <- append([]byte(nil), data...):
		return nil
	}
}

func TestUnifiedTunnelL4KeepsPerStackMemoryLimits(t *testing.T) {
	oldDefaults := [3]int{TCPBufMin, TCPBufDefault, TCPBufMax}
	for _, server := range []bool{false, true} {
		base, _ := newTestTransportPair(t)
		network, err := NewUnifiedTCPTunnel(base, server)
		if err != nil {
			t.Fatal(err)
		}
		defer network.UnifiedClose()
		var rcv tcpip.TCPReceiveBufferSizeRangeOption
		var snd tcpip.TCPSendBufferSizeRangeOption
		if err := network.gvisorStack.TransportProtocolOption(tcp.ProtocolNumber, &rcv); err != nil {
			t.Fatal(err)
		}
		if err := network.gvisorStack.TransportProtocolOption(tcp.ProtocolNumber, &snd); err != nil {
			t.Fatal(err)
		}
		if rcv.Min != 65536 || rcv.Default != 262144 || rcv.Max != 1048576 ||
			snd.Min != 65536 || snd.Default != 262144 || snd.Max != 1048576 {
			t.Fatalf("unexpected TCP limits: receive=%+v send=%+v", rcv, snd)
		}
		if network.exitMode != ExitModeL4 {
			t.Fatal("server must not require raw sockets or host networking")
		}
	}
	if [3]int{TCPBufMin, TCPBufDefault, TCPBufMax} != oldDefaults {
		t.Fatal("adapter mutated global upstream TCP defaults")
	}
}

func TestUnifiedTunnelLegacyEncryptedTCPRoundTrip(t *testing.T) {
	testUnifiedTCPRoundTrip(t, false)
}

func TestUnifiedTunnelLegacyEncryptedResponseAfterRequestEOF(t *testing.T) {
	testUnifiedTCPRoundTrip(t, true)
}

func testUnifiedTCPRoundTrip(t *testing.T, halfClose bool) {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverResult := make(chan error, 1)
	const request = "synthetic OpenFlux request"
	const response = "synthetic OpenFlux response"
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverResult <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		var data []byte
		if halfClose {
			data, err = io.ReadAll(conn)
		} else {
			data = make([]byte, len(request))
			_, err = io.ReadFull(conn, data)
		}
		if err == nil && string(data) != request {
			err = errors.New("request was not preserved by the tunnel")
		}
		if err == nil {
			_, err = io.WriteString(conn, response)
		}
		serverResult <- err
	}()
	clientBase, serverBase := newTestTransportPair(t)
	makeNetwork := func(base transport.Transport, server bool) (*UnifiedTCPTunnel, transport.Transport) {
		t.Helper()
		const key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
		enc, err := transport.NewEncryptedTransport(base, key, "synthetic-document", server)
		if err != nil {
			t.Fatal(err)
		}
		wire := transport.NewCompressedTransport(enc)
		network, err := NewUnifiedTCPTunnel(wire, server)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(network.UnifiedClose)
		t.Cleanup(func() { _ = wire.Stop() })
		return network, wire
	}
	client, clientWire := makeNetwork(clientBase, false)
	server, serverWire := makeNetwork(serverBase, true)
	// gVisor rejects incoming loopback IP packets. Translate only the test
	// destination, keeping every real socket and byte on this machine.
	server.unifiedExitForwarder(func(ctx context.Context, address string) (net.Conn, error) {
		if address != "192.0.2.1:443" {
			return nil, errors.New("unexpected test destination")
		}
		dialer := net.Dialer{Timeout: time.Second}
		return dialer.DialContext(ctx, "tcp4", listener.Addr().String())
	})
	if err := serverWire.Start(); err != nil {
		t.Fatal(err)
	}
	if err := clientWire.Start(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := client.UnifiedDialContext(ctx, "192.0.2.1:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(conn, request); err != nil {
		t.Fatal(err)
	}
	if halfClose {
		writer, ok := conn.(interface{ CloseWrite() error })
		if !ok {
			t.Fatal("TCP connection does not support half-close")
		}
		if err := writer.CloseWrite(); err != nil {
			t.Fatal(err)
		}
	}
	data := make([]byte, len(response))
	if _, err := io.ReadFull(conn, data); err != nil {
		t.Fatal(err)
	}
	if string(data) != response {
		t.Fatal("response was not preserved by the tunnel")
	}
	select {
	case err := <-serverResult:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("local server did not finish")
	}
}

func TestUnifiedTunnelRejectsLocalDNSAndCancelledDials(t *testing.T) {
	base, _ := newTestTransportPair(t)
	network, err := NewUnifiedTCPTunnel(base, false)
	if err != nil {
		t.Fatal(err)
	}
	defer network.UnifiedClose()
	for _, address := range []string{"example.invalid:443", "[::1]:443", "192.0.2.1:0", "192.0.2.1:65536", "invalid"} {
		if conn, err := network.UnifiedDialContext(context.Background(), address); err == nil {
			conn.Close()
			t.Fatalf("accepted invalid or non-IPv4 address %q", address)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if conn, err := network.UnifiedDialContext(ctx, "192.0.2.1:443"); err == nil {
		conn.Close()
		t.Fatal("accepted a cancelled dial")
	}
}
