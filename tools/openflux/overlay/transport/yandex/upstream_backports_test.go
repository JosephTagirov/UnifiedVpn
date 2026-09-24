// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"openflux/transport"
)

func localWebSocketPair(t *testing.T) (*websocket.Conn, *websocket.Conn) {
	t.Helper()
	accepted := make(chan *websocket.Conn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upgrader := websocket.Upgrader{}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err == nil {
			accepted <- conn
		}
	}))
	t.Cleanup(server.Close)
	dialer := websocket.Dialer{HandshakeTimeout: time.Second}
	client, _, err := dialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal("local WebSocket handshake failed")
	}
	t.Cleanup(func() { client.Close() })
	select {
	case peer := <-accepted:
		t.Cleanup(func() { peer.Close() })
		return client, peer
	case <-time.After(time.Second):
		t.Fatal("local WebSocket was not accepted")
		return nil, nil
	}
}

func TestYandexBackportWriterKeepsPendingPacketAcrossReconnect(t *testing.T) {
	failed, _ := localWebSocketPair(t)
	replacement, peer := localWebSocketPair(t)
	failed.Close()
	tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	tpt.BaseTransport.Start()
	queue := make(chan []byte, 2)
	tpt.session = &DocSession{Conn: failed, WriteQueue: queue}
	tpt.SetConnected(true)
	done := make(chan struct{})
	go func() { tpt.writerLoop(); close(done) }()
	defer func() {
		tpt.Stop()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("writer did not exit after Stop")
		}
	}()
	first, second := []byte("pending-packet"), []byte("queued-packet")
	if tpt.Send(first) != nil {
		t.Fatal("could not enqueue first packet")
	}
	deadline := time.Now().Add(time.Second)
	for tpt.IsConnected() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if tpt.IsConnected() {
		t.Fatal("failed write did not disconnect its session")
	}
	queue <- second
	if err := tpt.activateSession(&DocSession{
		Conn: replacement, WriteQueue: queue, UserID: "synthetic-user",
		Info: YandexDocsInfo{Token: "synthetic-token", DocID: "synthetic-document"},
	}); err != nil {
		t.Fatal("replacement authentication failed")
	}
	peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, tokenFrame, err := peer.ReadMessage()
	var token map[string]string
	if err != nil || !strings.HasPrefix(string(tokenFrame), "40") ||
		json.Unmarshal(tokenFrame[2:], &token) != nil || token["token"] != "synthetic-token" {
		t.Fatal("queued data preceded the authentication token")
	}
	_, authFrame, err := peer.ReadMessage()
	var auth []json.RawMessage
	var event string
	var details map[string]interface{}
	if err != nil || !strings.HasPrefix(string(authFrame), "42") ||
		json.Unmarshal(authFrame[2:], &auth) != nil || len(auth) != 2 ||
		json.Unmarshal(auth[0], &event) != nil || event != "message" ||
		json.Unmarshal(auth[1], &details) != nil || details["type"] != "auth" {
		t.Fatal("queued data preceded document authentication")
	}
	for _, want := range [][]byte{first, second} {
		kind, message, err := peer.ReadMessage()
		if err != nil || kind != websocket.TextMessage ||
			tpt.extractBase64String(string(message)) != base64.StdEncoding.EncodeToString(want) {
			t.Fatal("reconnect lost, reordered or changed a queued packet")
		}
	}
}

func TestYandexBackportIdleLoopsCancelOnStop(t *testing.T) {
	for _, loop := range []string{"writer", "pending writer", "keepalive", "reconnect wait"} {
		t.Run(loop, func(t *testing.T) {
			config := transport.DefaultConfig()
			config.KeepAliveInterval = time.Hour
			tpt := NewYandexDocsTransport(testDocumentURL, config)
			tpt.BaseTransport.Start()
			tpt.session = &DocSession{WriteQueue: make(chan []byte, 1)}
			if loop == "pending writer" {
				tpt.session.WriteQueue <- []byte("pending")
			}
			defer tpt.Stop()
			done := make(chan struct{})
			go func() {
				switch loop {
				case "writer", "pending writer":
					tpt.writerLoop()
				case "keepalive":
					tpt.keepAliveLoop()
				default:
					tpt.waitWhileRunning(time.Hour)
				}
				close(done)
			}()
			select {
			case <-done:
				t.Fatal("running loop exited before Stop")
			case <-time.After(30 * time.Millisecond):
			}
			tpt.Stop()
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("idle loop outlived Stop")
			}
		})
	}
}

func TestYandexBackportStopReleasesBlockedWriter(t *testing.T) {
	client, peer := localWebSocketPair(t)
	client.NetConn().(*net.TCPConn).SetWriteBuffer(1024)
	peer.NetConn().(*net.TCPConn).SetReadBuffer(1024)
	client.SetWriteDeadline(time.Now().Add(5 * time.Second))
	tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	tpt.BaseTransport.Start()
	defer tpt.Stop()
	session := &DocSession{Conn: client, WriteQueue: make(chan []byte, 1)}
	tpt.session = session
	tpt.SetConnected(true)
	done := make(chan struct{})
	go func() { tpt.writerLoop(); close(done) }()
	if tpt.Send(make([]byte, 4*1024*1024)) != nil {
		t.Fatal("could not enqueue synthetic large packet")
	}
	deadline := time.Now().Add(time.Second)
	locked := false
	for time.Now().Before(deadline) {
		if !session.writeMu.TryLock() {
			locked = true
			break
		}
		session.writeMu.Unlock()
		time.Sleep(time.Millisecond)
	}
	if !locked {
		t.Fatal("writer did not enter its blocking write")
	}
	tpt.Stop()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Stop did not release the blocked writer")
	}
}

func TestYandexBackportStoppedTransportRejectsLateSession(t *testing.T) {
	client, peer := localWebSocketPair(t)
	tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	tpt.BaseTransport.Start()
	tpt.Stop()
	if err := tpt.activateSession(&DocSession{Conn: client}); err == nil || tpt.IsConnected() || tpt.session != nil {
		t.Fatal("late session was published after Stop")
	}
	peer.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err := peer.ReadMessage()
	var timeout net.Error
	if err == nil || (errors.As(err, &timeout) && timeout.Timeout()) {
		t.Fatal("late connection was not closed")
	}
}

func TestYandexBackportStaleSessionCannotDisconnectReplacement(t *testing.T) {
	old, oldPeer := localWebSocketPair(t)
	current, currentPeer := localWebSocketPair(t)
	tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	tpt.BaseTransport.Start()
	defer tpt.Stop()
	tpt.session = &DocSession{Conn: current, WriteQueue: make(chan []byte, 1)}
	tpt.SetConnected(true)
	tpt.disconnectSession(&DocSession{Conn: old})
	if !tpt.IsConnected() {
		t.Fatal("old session changed the current session's connected state")
	}
	oldPeer.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err := oldPeer.ReadMessage()
	var timeout net.Error
	if err == nil || (errors.As(err, &timeout) && timeout.Timeout()) {
		t.Fatal("old connection was not closed")
	}
	if current.WriteMessage(websocket.TextMessage, []byte("alive")) != nil {
		t.Fatal("replacement connection was closed")
	}
	currentPeer.SetReadDeadline(time.Now().Add(time.Second))
	if _, data, err := currentPeer.ReadMessage(); err != nil || string(data) != "alive" {
		t.Fatal("replacement connection no longer usable")
	}
}

func TestYandexBackportReconnectDelayBounds(t *testing.T) {
	for attempt, base := range map[int]time.Duration{
		-1: 1500 * time.Millisecond, 0: 1500 * time.Millisecond, 1: 1500 * time.Millisecond,
		2: 3 * time.Second, 3: 6 * time.Second, 4: 12 * time.Second,
		5: 24 * time.Second, 100: 24 * time.Second,
	} {
		for i := 0; i < 100; i++ {
			got := reconnectBackoff(attempt)
			if got < base || got > base+base/2 {
				t.Fatal("reconnect delay outside the upstream backoff range")
			}
		}
	}
}
