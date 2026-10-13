// SPDX-License-Identifier: GPL-3.0-or-later
package tunnel

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type dnsTCPConn struct{ net.Conn }

func (c dnsTCPConn) LocalAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(10, 10, 10, 2), Port: 10001}
}
func (c dnsTCPConn) RemoteAddr() net.Addr { return &net.TCPAddr{IP: net.IPv4(1, 1, 1, 1), Port: 53} }

func TestSessionDNSIPv4UsesTunnel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var calls atomic.Int32
	dial := func(ctx context.Context, address string) (net.Conn, error) {
		calls.Add(1)
		if address != "1.1.1.1:53" {
			t.Errorf("unexpected DNS destination")
		}
		client, server := net.Pipe()
		go func() {
			defer server.Close()
			server.SetDeadline(time.Now().Add(2 * time.Second))
			var header [2]byte
			if _, err := io.ReadFull(server, header[:]); err != nil {
				return
			}
			packet := make([]byte, binary.BigEndian.Uint16(header[:]))
			if _, err := io.ReadFull(server, packet); err != nil {
				return
			}
			var request dnsmessage.Message
			if err := request.Unpack(packet); err != nil {
				t.Error(err)
				return
			}
			if len(request.Questions) != 1 || request.Questions[0].Type != dnsmessage.TypeA {
				t.Error("resolver did not request IPv4 only")
				return
			}
			question := request.Questions[0]
			response := dnsmessage.Message{
				Header:    dnsmessage.Header{ID: request.ID, Response: true, RecursionDesired: true, RecursionAvailable: true},
				Questions: request.Questions,
				Answers: []dnsmessage.Resource{{
					Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30},
					Body:   &dnsmessage.AResource{A: [4]byte{203, 0, 113, 42}},
				}},
			}
			encoded, err := response.Pack()
			if err != nil {
				t.Error(err)
				return
			}
			binary.BigEndian.PutUint16(header[:], uint16(len(encoded)))
			server.Write(append(header[:], encoded...))
		}()
		return dnsTCPConn{client}, nil
	}
	ip, err := sessionResolveIPv4(ctx, "session-probe.invalid", dial)
	if err != nil || !ip.Equal(net.IPv4(203, 0, 113, 42)) {
		t.Fatalf("unexpected result: %v %v", ip, err)
	}
	if calls.Load() == 0 {
		t.Fatal("tunnel DNS was not used")
	}
}

func TestSessionDNSLiteralsDoNotRecurse(t *testing.T) {
	dial := func(context.Context, string) (net.Conn, error) { t.Fatal("literal triggered DNS"); return nil, nil }
	ip, err := sessionResolveIPv4(context.Background(), "1.1.1.1", dial)
	if err != nil || !ip.Equal(net.IPv4(1, 1, 1, 1)) {
		t.Fatal("literal failed")
	}
	if _, err := sessionResolveIPv4(context.Background(), "2001:db8::1", dial); err == nil {
		t.Fatal("IPv6 accepted")
	}
}

func TestSessionDNSFailureDoesNotFallBackToHost(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	var calls atomic.Int32
	dial := func(context.Context, string) (net.Conn, error) {
		calls.Add(1)
		return nil, errors.New("private internal detail")
	}
	_, err := sessionResolveIPv4(ctx, "session-probe.invalid", dial)
	if calls.Load() == 0 || err == nil || err.Error() != "tunnel DNS resolution failed" {
		t.Fatal("DNS did not fail closed with a sanitized error")
	}
}
