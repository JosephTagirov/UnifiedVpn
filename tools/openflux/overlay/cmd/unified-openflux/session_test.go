// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"universal-bypass-tool/transport"
)

type memoryTransport struct {
	mu        sync.Mutex
	peer      *memoryTransport
	callback  func([]byte)
	connected bool
	starts    int
	stops     int
	sent      [][]byte
	notice    chan struct{}
	onSend    func([]byte) bool
}

func (m *memoryTransport) Start() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.connected = true
	m.starts++
	return nil
}

func (m *memoryTransport) Stop() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.connected = false
	m.stops++
	return nil
}

func (m *memoryTransport) Send(packet []byte) error {
	packet = append([]byte(nil), packet...)
	m.mu.Lock()
	if !m.connected {
		m.mu.Unlock()
		return errors.New("in-memory transport is stopped")
	}
	m.sent = append(m.sent, packet)
	peer, hook := m.peer, m.onSend
	m.mu.Unlock()
	select {
	case m.notice <- struct{}{}:
	default:
	}
	if hook != nil && !hook(packet) {
		return nil
	}
	if peer != nil {
		peer.deliver(packet)
	}
	return nil
}

func (m *memoryTransport) Receive(callback func([]byte)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callback = callback
}

func (m *memoryTransport) deliver(packet []byte) {
	m.mu.Lock()
	callback, connected := m.callback, m.connected
	m.mu.Unlock()
	if connected && callback != nil {
		callback(append([]byte(nil), packet...))
	}
}

func (m *memoryTransport) IsConnected() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.connected
}

func (m *memoryTransport) Stats() transport.TransportStats {
	m.mu.Lock()
	defer m.mu.Unlock()
	return transport.TransportStats{Connected: m.connected, PacketsSent: uint64(len(m.sent))}
}

func (m *memoryTransport) packets() [][]byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := make([][]byte, len(m.sent))
	for i, packet := range m.sent {
		result[i] = append([]byte(nil), packet...)
	}
	return result
}

func (m *memoryTransport) hook(callback func([]byte) bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.onSend = callback
}

type sessionPair struct {
	client, server             *peerSession
	clientWire, serverWire     *memoryTransport
	clientStream, serverStream transport.Transport
	clientData, serverData     chan []byte
}

func newSessionPair(t *testing.T, clientKey, serverKey string, serverDirection bool) *sessionPair {
	t.Helper()
	clientWire := &memoryTransport{notice: make(chan struct{}, 1)}
	serverWire := &memoryTransport{notice: make(chan struct{}, 1)}
	clientWire.peer, serverWire.peer = serverWire, clientWire
	const publicContext = "https://docs.yandex.ru/public-in-memory-test"
	clientEncrypted, err := transport.NewEncryptedTransport(clientWire, clientKey, publicContext, false)
	if err != nil {
		t.Fatal(err)
	}
	serverEncrypted, err := transport.NewEncryptedTransport(serverWire, serverKey, publicContext, serverDirection)
	if err != nil {
		t.Fatal(err)
	}
	pair := &sessionPair{
		clientWire: clientWire, serverWire: serverWire,
		clientStream: transport.NewCompressedTransport(clientEncrypted),
		serverStream: transport.NewCompressedTransport(serverEncrypted),
		clientData:   make(chan []byte, 8), serverData: make(chan []byte, 8),
	}
	pair.client = newPeerSession(pair.clientStream, false)
	pair.server = newPeerSession(pair.serverStream, true)
	pair.client.Receive(func(packet []byte) { pair.clientData <- append([]byte(nil), packet...) })
	pair.server.Receive(func(packet []byte) { pair.serverData <- append([]byte(nil), packet...) })
	if err := pair.server.Start(); err != nil {
		t.Fatal(err)
	}
	if err := pair.client.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = pair.client.Stop()
		_ = pair.server.Stop()
	})
	return pair
}

func normalSessionPair(t *testing.T) *sessionPair {
	t.Helper()
	return newSessionPair(t, strings.Repeat("12", 32), strings.Repeat("12", 32), true)
}

func awaitPackets(t *testing.T, wire *memoryTransport, count int) [][]byte {
	t.Helper()
	timer := time.NewTimer(3 * time.Second)
	defer timer.Stop()
	for {
		if packets := wire.packets(); len(packets) >= count {
			return packets
		}
		select {
		case <-wire.notice:
		case <-timer.C:
			t.Fatal("timed out waiting for an in-memory transport record")
		}
	}
}

func beginWait(t *testing.T, peer *peerSession, ctx context.Context) <-chan error {
	t.Helper()
	ctx, cancel := context.WithCancel(ctx)
	result, finished := make(chan error, 1), make(chan struct{})
	go func() {
		defer close(finished)
		result <- peer.waitPeer(ctx)
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-finished:
		case <-time.After(time.Second):
			t.Error("handshake goroutine did not stop after cancellation")
		}
	})
	return result
}

func awaitResult(t *testing.T, result <-chan error) error {
	t.Helper()
	select {
	case err := <-result:
		return err
	case <-time.After(3 * time.Second):
		t.Fatal("handshake did not return")
		return nil
	}
}

func expectNoData(t *testing.T, packets <-chan []byte) {
	t.Helper()
	select {
	case <-packets:
		t.Fatal("application data escaped the peer confirmation gate")
	default:
	}
}

func expectData(t *testing.T, packets <-chan []byte, expected []byte) {
	t.Helper()
	select {
	case packet := <-packets:
		if !bytes.Equal(packet, expected) {
			t.Fatal("application data changed across encryption/compression")
		}
	case <-time.After(time.Second):
		t.Fatal("confirmed application data was not delivered")
	}
}

func successfulWait(t *testing.T, peer *peerSession) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := peer.waitPeer(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestPeerSessionEncryptedHandshakeAndCompressedData(t *testing.T) {
	pair := normalSessionPair(t)
	payload := bytes.Repeat([]byte("public application payload "), 64)
	if err := pair.clientStream.Send(payload); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.serverData)
	if err := pair.serverStream.Send(payload); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.clientData)
	successfulWait(t, pair.client)
	expectNoData(t, pair.clientData)
	expectNoData(t, pair.serverData)
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
			header := []byte{'O', 'F', 'X', 1, byte(direction)}
			if !bytes.HasPrefix(packet, header) || len(packet) < 33 || bytes.Equal(packet, payload) {
				t.Fatal("wire record is not a directional encrypted record")
			}
		}
	}
}

func TestPeerSessionWrongKeyOrDirectionCannotConfirm(t *testing.T) {
	for _, test := range []struct {
		name            string
		serverKey       string
		serverDirection bool
	}{
		{"wrong key", strings.Repeat("34", 32), true},
		{"wrong direction", strings.Repeat("12", 32), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			pair := newSessionPair(t, strings.Repeat("12", 32), test.serverKey, test.serverDirection)
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
			defer cancel()
			if err := pair.client.waitPeer(ctx); err == nil {
				t.Fatal("an incompatible encrypted peer was accepted")
			}
			if len(pair.serverWire.packets()) != 0 {
				t.Fatal("the server answered unauthenticated input")
			}
			expectNoData(t, pair.clientData)
			expectNoData(t, pair.serverData)
		})
	}
}

func TestPeerSessionNeverFallsBackToPlaintext(t *testing.T) {
	pair := normalSessionPair(t)
	hello := controlRecord('H', bytes.Repeat([]byte{7}, 32))
	for _, packet := range [][]byte{hello, append([]byte{0}, hello...), []byte("public plaintext data")} {
		pair.serverWire.deliver(packet)
		pair.clientWire.deliver(packet)
	}
	if len(pair.serverWire.packets()) != 0 {
		t.Fatal("the server answered a plaintext hello")
	}
	if err := pair.clientStream.Send([]byte("authenticated data without hello")); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.clientData)
	expectNoData(t, pair.serverData)
}

func TestPeerSessionMalformedAndWrongRoleControlsDoNotOpenServerGate(t *testing.T) {
	pair := normalSessionPair(t)
	for _, packet := range [][]byte{
		append([]byte(nil), controlPrefix...),
		controlRecord('H', make([]byte, 31)),
		controlRecord('H', make([]byte, 33)),
		controlRecord('W', make([]byte, 32)),
		controlRecord('X', make([]byte, 32)),
	} {
		if err := pair.clientStream.Send(packet); err != nil {
			t.Fatal(err)
		}
	}
	if len(pair.serverWire.packets()) != 0 {
		t.Fatal("the server answered an invalid control record")
	}
	if err := pair.clientStream.Send([]byte("still gated")); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.serverData)
	successfulWait(t, pair.client)
	if err := pair.client.Send([]byte("now confirmed")); err != nil {
		t.Fatal(err)
	}
	expectData(t, pair.serverData, []byte("now confirmed"))
}

func TestPeerSessionFreshChallengeRejectsStaleWelcome(t *testing.T) {
	pair := normalSessionPair(t)
	pair.serverWire.hook(func([]byte) bool { return false })
	firstCtx, firstCancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer firstCancel()
	if err := pair.client.waitPeer(firstCtx); err == nil {
		t.Fatal("a dropped welcome unexpectedly completed the handshake")
	}
	oldWelcome := awaitPackets(t, pair.serverWire, 1)[0]
	result := beginWait(t, pair.client, context.Background())
	freshWelcome := awaitPackets(t, pair.serverWire, 2)[1]
	// This first delivery passes upstream's nonce replay filter; the session
	// challenge, rather than the encryption replay cache, must reject it.
	pair.clientWire.deliver(oldWelcome)
	select {
	case <-result:
		t.Fatal("a previous attempt's welcome completed a new challenge")
	case <-time.After(20 * time.Millisecond):
	}
	pair.clientWire.deliver(freshWelcome)
	if err := awaitResult(t, result); err != nil {
		t.Fatal(err)
	}
	pair.serverWire.hook(nil)
	if err := pair.server.Send([]byte("fresh peer confirmed")); err != nil {
		t.Fatal(err)
	}
	expectData(t, pair.clientData, []byte("fresh peer confirmed"))
}

func TestPeerSessionExpiredAttemptIgnoresLateWelcome(t *testing.T) {
	pair := normalSessionPair(t)
	pair.serverWire.hook(func([]byte) bool { return false })
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	if err := pair.client.waitPeer(ctx); err == nil {
		t.Fatal("the handshake must time out when all welcomes are dropped")
	}
	pair.clientWire.deliver(awaitPackets(t, pair.serverWire, 1)[0])
	pair.serverWire.hook(nil)
	if err := pair.serverStream.Send([]byte("late welcome must not authorize data")); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.clientData)
}

func TestPeerSessionAlreadyCancelledDoesNotSendHello(t *testing.T) {
	pair := normalSessionPair(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := pair.client.waitPeer(ctx); err == nil {
		t.Fatal("an already-cancelled handshake succeeded")
	}
	if len(pair.clientWire.packets()) != 0 || len(pair.serverWire.packets()) != 0 {
		t.Fatal("an already-cancelled handshake touched the transport")
	}
}

func TestPeerSessionSynchronousWelcomeCannotOverrideCancellation(t *testing.T) {
	pair := normalSessionPair(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	pair.serverWire.hook(func([]byte) bool {
		cancel()
		return true
	})
	if err := pair.client.waitPeer(ctx); err == nil {
		t.Fatal("a synchronous welcome overrode a cancelled context")
	}
	pair.serverWire.hook(nil)
	if err := pair.serverStream.Send([]byte("cancelled attempt data")); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.clientData)
}

func TestPeerSessionOnlyOneHandshakeCanBePending(t *testing.T) {
	pair := normalSessionPair(t)
	pair.serverWire.hook(func([]byte) bool { return false })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	first := beginWait(t, pair.client, ctx)
	awaitPackets(t, pair.serverWire, 1)
	secondCtx, secondCancel := context.WithTimeout(context.Background(), time.Second)
	defer secondCancel()
	if err := pair.client.waitPeer(secondCtx); err == nil {
		t.Fatal("a concurrent handshake was accepted")
	}
	if len(pair.clientWire.packets()) != 1 {
		t.Fatal("a second pending handshake emitted another challenge")
	}
	cancel()
	if err := awaitResult(t, first); err == nil {
		t.Fatal("the cancelled original handshake succeeded")
	}
}

func TestPeerSessionPeriodicFailureClosesPreviouslyConfirmedGate(t *testing.T) {
	pair := normalSessionPair(t)
	successfulWait(t, pair.client)
	pair.serverWire.hook(func([]byte) bool { return false })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := beginWait(t, pair.client, ctx)
	awaitPackets(t, pair.serverWire, 2)
	pair.serverWire.hook(nil)
	if err := pair.serverStream.Send([]byte("data during liveness probe")); err != nil {
		t.Fatal(err)
	}
	expectData(t, pair.clientData, []byte("data during liveness probe"))
	cancel()
	if err := awaitResult(t, result); err == nil {
		t.Fatal("a liveness probe with no welcome succeeded")
	}
	if err := pair.serverStream.Send([]byte("data after failed probe")); err != nil {
		t.Fatal(err)
	}
	expectNoData(t, pair.clientData)
}

func TestPeerSessionStopCancelsWaitAndCannotRestart(t *testing.T) {
	pair := normalSessionPair(t)
	pair.serverWire.hook(func([]byte) bool { return false })
	result := beginWait(t, pair.client, context.Background())
	awaitPackets(t, pair.serverWire, 1)
	if err := pair.client.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := awaitResult(t, result); err == nil {
		t.Fatal("a stopped session retained a successful pending wait")
	}
	if pair.client.IsConnected() || pair.client.Stats().Connected {
		t.Fatal("Stop did not reach the underlying transport")
	}
	if err := pair.client.Start(); err == nil {
		t.Fatal("a permanently stopped peer session restarted")
	}
	if err := pair.client.waitPeer(context.Background()); err == nil {
		t.Fatal("a stopped session accepted a new handshake")
	}
	pair.clientWire.mu.Lock()
	starts := pair.clientWire.starts
	pair.clientWire.mu.Unlock()
	if starts != 1 {
		t.Fatal("Start after Stop reached the underlying transport")
	}
}

func TestPeerSessionReplayDoesNotRedeliverData(t *testing.T) {
	pair := normalSessionPair(t)
	successfulWait(t, pair.client)
	if err := pair.client.Send([]byte("deliver once")); err != nil {
		t.Fatal(err)
	}
	expectData(t, pair.serverData, []byte("deliver once"))
	packets := pair.clientWire.packets()
	pair.serverWire.deliver(packets[len(packets)-1])
	expectNoData(t, pair.serverData)
	welcomeCount := len(pair.serverWire.packets())
	pair.serverWire.deliver(packets[0])
	if len(pair.serverWire.packets()) != welcomeCount {
		t.Fatal("a duplicate encrypted hello caused another welcome")
	}
}

func TestPeerSessionStopDropsAlreadyQueuedApplicationDelivery(t *testing.T) {
	pair := normalSessionPair(t)
	successfulWait(t, pair.client)
	pair.clientWire.hook(func([]byte) bool { return false })
	if err := pair.clientStream.Send([]byte("delivery already queued during shutdown")); err != nil {
		t.Fatal(err)
	}
	packets := pair.clientWire.packets()
	pair.serverWire.mu.Lock()
	queuedCallback := pair.serverWire.callback
	pair.serverWire.mu.Unlock()
	if err := pair.server.Stop(); err != nil {
		t.Fatal(err)
	}
	queuedCallback(packets[len(packets)-1])
	expectNoData(t, pair.serverData)
}
