// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import (
	"context"
	"errors"
	"net"
	"strconv"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

func (t *TCPTunnel) UnifiedReady() error {
	if t.gvisorStack == nil || (t.isExitNode && t.rawEP == nil) {
		return errors.New("OpenFlux network stack could not initialize")
	}
	return nil
}

func (t *TCPTunnel) UnifiedClose() {
	if t.rawEP != nil {
		t.rawEP.Close()
	}
	if t.gvisorStack != nil {
		t.gvisorStack.Close()
	}
}

// The caller resolves destination names over tunnel TCP. Do not use the
// upstream DialTCP's local-system resolver for user destination names.
func (t *TCPTunnel) UnifiedDialContext(ctx context.Context, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, errors.New("invalid tunnel destination")
	}
	ip := net.ParseIP(host).To4()
	p, err := strconv.Atoi(port)
	if ip == nil || err != nil || p < 1 || p > 65535 {
		return nil, errors.New("tunnel requires an IPv4 destination")
	}
	return gonet.DialContextTCP(ctx, t.gvisorStack, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{ip[0], ip[1], ip[2], ip[3]}), Port: uint16(p),
	}, ipv4.ProtocolNumber)
}
