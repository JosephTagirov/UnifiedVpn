// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	"openflux/transport"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"
)

type UnifiedTCPTunnel struct {
	*TCPTunnel
	ctx       context.Context
	cancel    context.CancelFunc
	closeOnce sync.Once
}

// Keep the established memory limits per stack instead of inheriting the
// upstream 64-fold buffer increase. Configure before starting the transport.
func NewUnifiedTCPTunnel(trans transport.Transport, server bool) (*UnifiedTCPTunnel, error) {
	ctx, cancel := context.WithCancel(context.Background())
	t := &UnifiedTCPTunnel{TCPTunnel: NewTCPTunnelMode(trans, server, ExitModeL4), ctx: ctx, cancel: cancel}
	if err := t.UnifiedReady(); err != nil {
		t.UnifiedClose()
		return nil, err
	}
	rcv := tcpip.TCPReceiveBufferSizeRangeOption{Min: 65536, Default: 262144, Max: 1048576}
	snd := tcpip.TCPSendBufferSizeRangeOption{Min: 65536, Default: 262144, Max: 1048576}
	if t.gvisorStack.SetTransportProtocolOption(tcp.ProtocolNumber, &rcv) != nil ||
		t.gvisorStack.SetTransportProtocolOption(tcp.ProtocolNumber, &snd) != nil {
		t.UnifiedClose()
		return nil, errors.New("OpenFlux TCP memory limits could not initialize")
	}
	if server {
		t.unifiedExitForwarder(func(ctx context.Context, address string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 10 * time.Second}
			return dialer.DialContext(ctx, "tcp4", address)
		})
	}
	return t, nil
}

// Preserve TCP half-close: request EOF must not discard a later response.
// The dial function also permits loopback-only tests without external traffic.
func (t *UnifiedTCPTunnel) unifiedExitForwarder(dial func(context.Context, string) (net.Conn, error)) {
	fwd := tcp.NewForwarder(t.gvisorStack, 0, 8192, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		remote, dialErr := dial(t.ctx, net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort))))
		if dialErr != nil {
			r.Complete(true)
			return
		}
		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil {
			remote.Close()
			r.Complete(true)
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&wq, ep)
		stopClose := context.AfterFunc(t.ctx, func() {
			local.Close()
			remote.Close()
		})
		go func() {
			defer stopClose()
			defer local.Close()
			defer remote.Close()
			if conn, ok := remote.(*net.TCPConn); ok {
				_ = conn.SetNoDelay(true)
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				unifiedCopyHalfClose(remote, local)
			}()
			unifiedCopyHalfClose(local, remote)
			<-done
		}()
	})
	t.gvisorStack.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)
}

func unifiedCopyHalfClose(dst, src net.Conn) {
	_, err := io.CopyBuffer(dst, src, make([]byte, 32*1024))
	if err == nil {
		if writer, ok := dst.(interface{ CloseWrite() error }); ok {
			if writer.CloseWrite() == nil {
				return
			}
		}
	}
	_ = dst.Close()
	_ = src.Close()
}

func (t *UnifiedTCPTunnel) UnifiedReady() error {
	if t.gvisorStack == nil || t.tunnelEP == nil || t.exitMode != ExitModeL4 {
		return errors.New("OpenFlux network stack could not initialize")
	}
	if _, ok := t.gvisorStack.NICInfo()[1]; !ok {
		return errors.New("OpenFlux network interface could not initialize")
	}
	return nil
}

func (t *UnifiedTCPTunnel) UnifiedClose() {
	t.closeOnce.Do(func() {
		t.cancel()
		if t.gvisorStack != nil {
			t.gvisorStack.Close()
		}
	})
}

// The caller resolves destination names over tunnel TCP. Do not use the
// upstream DialTCP's local-system resolver for user destination names.
func (t *UnifiedTCPTunnel) UnifiedDialContext(ctx context.Context, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, errors.New("invalid tunnel destination")
	}
	ip := net.ParseIP(host).To4()
	p, err := strconv.Atoi(port)
	if ip == nil || err != nil || p < 1 || p > 65535 {
		return nil, errors.New("tunnel requires an IPv4 destination")
	}
	conn, err := gonet.DialContextTCP(ctx, t.gvisorStack, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{ip[0], ip[1], ip[2], ip[3]}), Port: uint16(p),
	}, ipv4.ProtocolNumber)
	if err != nil {
		return nil, err
	}
	return conn, nil
}
