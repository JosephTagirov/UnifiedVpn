// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import (
	"context"
	"errors"
	"net"
)

// Resolve only IPv4 because this stack forwards IPv4 packets. DNS itself must
// use the encrypted tunnel; falling back to the host leaks destinations and
// makes a SOCKS5 hostname request depend on the local carrier's resolver.
func sessionResolveIPv4(ctx context.Context, host string, dial func(context.Context, string) (net.Conn, error)) (net.IP, error) {
	if literal := net.ParseIP(host); literal != nil {
		if ip := literal.To4(); ip != nil {
			return ip, nil
		}
		return nil, errors.New("IPv6 not supported")
	}
	resolver := &net.Resolver{
		PreferGo:     true,
		StrictErrors: true,
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return dial(ctx, "1.1.1.1:53")
		},
	}
	ips, err := resolver.LookupIP(ctx, "ip4", host)
	if err != nil {
		return nil, errors.New("tunnel DNS resolution failed")
	}
	for _, candidate := range ips {
		if ip := candidate.To4(); ip != nil {
			return ip, nil
		}
	}
	return nil, errors.New("tunnel DNS returned no IPv4 address")
}
