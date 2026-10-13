// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	"openflux/transport"
)

func codecPointer(codec string) *string {
	if codec == "" {
		return nil
	}
	return &codec
}

func newWireSessionPair(t *testing.T, clientCodec, serverCodec string, wrongKey bool) *sessionPair {
	t.Helper()
	clientWire := &memoryTransport{notice: make(chan struct{}, 1)}
	serverWire := &memoryTransport{notice: make(chan struct{}, 1)}
	clientWire.peer, serverWire.peer = serverWire, clientWire
	clientConfig := validClientConfig()
	clientConfig.WireCodec = codecPointer(clientCodec)
	serverConfig := clientConfig
	serverConfig.Mode, serverConfig.WireCodec = "server", codecPointer(serverCodec)
	if wrongKey {
		serverConfig.EncryptionKey = strings.Repeat("cd", 32)
	}
	clientStream, err := newWireTransport(clientConfig, clientWire)
	if err != nil {
		t.Fatal(err)
	}
	serverStream, err := newWireTransport(serverConfig, serverWire)
	if err != nil {
		t.Fatal(err)
	}
	pair := &sessionPair{
		clientWire: clientWire, serverWire: serverWire,
		clientStream: clientStream, serverStream: serverStream,
		clientData: make(chan []byte, 8), serverData: make(chan []byte, 8),
	}
	pair.client, pair.server = newPeerSession(clientStream, false), newPeerSession(serverStream, true)
	pair.client.Receive(func(packet []byte) { pair.clientData <- append([]byte(nil), packet...) })
	pair.server.Receive(func(packet []byte) { pair.serverData <- append([]byte(nil), packet...) })
	t.Cleanup(func() { _ = pair.client.Stop(); _ = pair.server.Stop() })
	if err := pair.server.Start(); err != nil {
		t.Fatal(err)
	}
	if err := pair.client.Start(); err != nil {
		t.Fatal(err)
	}
	return pair
}

func TestSelectedCodecEncryptedPeerRoundTrip(t *testing.T) {
	for _, codecs := range [][2]string{{"", legacyWireCodec}, {legacyWireCodec, ""}, {batchWireCodec, batchWireCodec}} {
		t.Run(codecs[0]+"-"+codecs[1], func(t *testing.T) {
			pair := newWireSessionPair(t, codecs[0], codecs[1], false)
			successfulWait(t, pair.client)
			payload := bytes.Repeat([]byte("synthetic-private-payload-not-on-the-wire"), 64)
			if err := pair.client.Send(payload); err != nil {
				t.Fatal(err)
			}
			expectData(t, pair.serverData, payload)
			if err := pair.server.Send(payload); err != nil {
				t.Fatal(err)
			}
			expectData(t, pair.clientData, payload)
			for direction, wire := range []*memoryTransport{pair.clientWire, pair.serverWire} {
				for _, packet := range wire.packets() {
					if len(packet) < 33 || !bytes.Equal(packet[:5], []byte{'O', 'F', 'X', 1, byte(direction)}) || bytes.Contains(packet, payload[:40]) {
						t.Fatal("document transport received plaintext or an unexpected cipher envelope")
					}
				}
			}
			if err := pair.client.Stop(); err != nil {
				t.Fatal(err)
			}
			if err := pair.client.Send(payload); err == nil {
				t.Fatal("send succeeded after Stop")
			}
		})
	}
}

func TestSelectedCodecMismatchNeverConfirmsPeer(t *testing.T) {
	for _, test := range []struct {
		client, server string
		wrongKey       bool
	}{{batchWireCodec, legacyWireCodec, false}, {legacyWireCodec, batchWireCodec, false}, {batchWireCodec, batchWireCodec, true}} {
		t.Run(test.client+"-"+test.server+"-wrong-key-"+map[bool]string{false: "no", true: "yes"}[test.wrongKey], func(t *testing.T) {
			pair := newWireSessionPair(t, test.client, test.server, test.wrongKey)
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()
			if err := pair.client.waitPeer(ctx); err == nil {
				t.Fatal("incompatible peer reached readiness")
			}
			if pair.client.Send([]byte("must-not-send")) == nil || pair.server.Send([]byte("must-not-send")) == nil {
				t.Fatal("mismatched codec or key bypassed the peer gate")
			}
			expectNoData(t, pair.clientData)
			expectNoData(t, pair.serverData)
		})
	}
}

func TestSelectedCodecAlwaysWrapsEncryption(t *testing.T) {
	for _, codec := range []string{"", legacyWireCodec, batchWireCodec} {
		c := validClientConfig()
		c.WireCodec = codecPointer(codec)
		wire, err := newWireTransport(c, &memoryTransport{})
		if err != nil {
			t.Fatal(err)
		}
		var encrypted transport.Transport
		switch value := wire.(type) {
		case *transport.CompressedTransport:
			if codec == batchWireCodec {
				t.Fatal("batch silently became legacy")
			}
			encrypted = value.Transport
		case *transport.BatchedTransport:
			if codec != batchWireCodec {
				t.Fatal("legacy silently became batch")
			}
			encrypted = value.Transport
		default:
			t.Fatal("unexpected codec implementation")
		}
		if _, ok := encrypted.(*transport.EncryptedTransport); !ok {
			t.Fatal("codec was placed outside mandatory encryption")
		}
		_ = wire.Stop()
		c.EncryptionKey = ""
		if _, err := newWireTransport(c, &memoryTransport{}); err == nil {
			t.Fatal("plaintext codec accepted")
		}
	}
	c := validClientConfig()
	c.Transport, c.WireCodec = "vyandex", codecPointer(batchWireCodec)
	if _, err := newWireTransport(c, &memoryTransport{}); err == nil {
		t.Fatal("batch accepted on Volga without configuration validation")
	}
	c.WireCodec = codecPointer("unsupported")
	if _, err := newWireTransport(c, &memoryTransport{}); err == nil {
		t.Fatal("unknown codec accepted without configuration validation")
	}
}

func TestBatchPeerStopCancelsBlockingTransportStart(t *testing.T) {
	inner := &blockingStartTransport{entered: make(chan struct{}), cancel: make(chan struct{})}
	c := validClientConfig()
	c.WireCodec = codecPointer(batchWireCodec)
	wire, err := newWireTransport(c, inner)
	if err != nil {
		t.Fatal(err)
	}
	peer := newPeerSession(wire, false)
	started, stopped := make(chan error, 1), make(chan error, 1)
	go func() { started <- peer.Start() }()
	<-inner.entered
	go func() { stopped <- peer.Stop() }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("batch Stop did not cancel inner startup")
	}
	if <-started == nil {
		t.Fatal("cancelled batch startup succeeded")
	}
}
