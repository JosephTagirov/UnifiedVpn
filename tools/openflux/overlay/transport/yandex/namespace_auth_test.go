// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"openflux/transport"
)

func namespaceCandidateFrames(t *testing.T, peer *websocket.Conn) <-chan string {
	t.Helper()
	frames := make(chan string, 128)
	done := make(chan struct{})
	exited := make(chan struct{})
	t.Cleanup(func() {
		close(done)
		_ = peer.Close()
		select {
		case <-exited:
		case <-time.After(time.Second):
			t.Error("synthetic peer reader did not stop")
		}
	})
	go func() {
		defer close(exited)
		defer close(frames)
		for {
			_, frame, err := peer.ReadMessage()
			if err != nil {
				return
			}
			select {
			case frames <- string(frame):
			case <-done:
				return
			}
		}
	}()
	return frames
}

func namespaceCandidateRead(t *testing.T, frames <-chan string) string {
	t.Helper()
	select {
	case value, ok := <-frames:
		if !ok {
			t.Fatal("synthetic peer socket closed before expected packet")
		}
		return value
	case <-time.After(time.Second):
		t.Fatal("synthetic peer did not receive expected packet")
		return ""
	}
}

func namespaceCandidateQuiet(t *testing.T, frames <-chan string, phase string) {
	t.Helper()
	select {
	case <-frames:
		t.Fatal("client sent a packet before " + phase)
	case <-time.After(40 * time.Millisecond):
	}
}

func namespaceCandidateRequireAuth(t *testing.T, frame string) {
	t.Helper()
	message, ok := parseClassicCoauthoring([]byte(frame))
	if !ok || message.Type != "auth" {
		t.Fatal("document authentication frame was not received")
	}
}

func TestClassicAuthCandidateWaitsForNamespaceAndDocumentAcceptance(t *testing.T) {
	client, peer := localWebSocketPair(t)
	frames := namespaceCandidateFrames(t, peer)
	config := transport.DefaultConfig()
	config.KeepAliveInterval = 5 * time.Millisecond
	tr := NewYandexDocsTransport(testDocumentURL, config)
	_ = tr.BaseTransport.Start()
	defer tr.Stop()
	queue := make(chan []byte, 2)
	queue <- []byte("synthetic-pending-data")
	session := &DocSession{Conn: client, WriteQueue: queue, UserID: "synthetic-user",
		Info: YandexDocsInfo{Token: "synthetic-token", DocID: "synthetic-document"}}
	activated := make(chan error, 1)
	go tr.keepAliveLoop()
	go func() {
		err := tr.activateSession(session)
		activated <- err
		if err == nil {
			tr.writerLoop()
		}
	}()
	if !strings.HasPrefix(namespaceCandidateRead(t, frames), "40{") {
		t.Fatal("namespace CONNECT was not the first packet")
	}
	namespaceCandidateQuiet(t, frames, "namespace acknowledgment")
	if tr.IsConnected() {
		t.Fatal("transport connected before namespace acknowledgment")
	}
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`40{"sid":"opaque+sid/="}`))
	namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
	namespaceCandidateQuiet(t, frames, "document authentication")
	if tr.IsConnected() {
		t.Fatal("transport connected before document authentication")
	}
	select {
	case <-activated:
		t.Fatal("activation returned before document authentication")
	default:
	}
	_ = peer.WriteMessage(websocket.TextMessage, []byte("2"))
	if namespaceCandidateRead(t, frames) != "3" {
		t.Fatal("Engine.IO ping was not answered during document authentication")
	}
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"connectState","waitAuth":true}]`))
	unlock := `42["message",{"type":"unLockDocument","unlock":true,"isSave":false,"releaseLocks":false}]`
	if namespaceCandidateRead(t, frames) != unlock {
		t.Fatal("pre-auth co-authoring lock was not acknowledged safely")
	}
	namespaceCandidateQuiet(t, frames, "document authentication after waitAuth")
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`))
	if namespaceCandidateRead(t, frames) != unlock {
		t.Fatal("accepted document authentication was not acknowledged safely")
	}
	select {
	case err := <-activated:
		if err != nil || !tr.IsConnected() {
			t.Fatal("accepted document did not activate")
		}
	case <-time.After(time.Second):
		t.Fatal("accepted document activation timed out")
	}
	want := base64.StdEncoding.EncodeToString([]byte("synthetic-pending-data"))
	for i := 0; i < 10; i++ {
		frame := namespaceCandidateRead(t, frames)
		if tr.extractBase64String(frame) == want {
			return
		}
	}
	t.Fatal("queued traffic was not sent after document authentication")
}

func TestClassicAuthCandidateNamespaceResponseShape(t *testing.T) {
	for _, response := range []string{`40`, `40{"sid":"opaque+sid/=","pid":"optional"}`, `40{"sid":"opaque_id"}`} {
		if !classicNamespaceAccepted([]byte(response)) {
			t.Fatal("valid root namespace acknowledgment rejected")
		}
	}
	for _, response := range []string{``, `0{"sid":"engine-only"}`, `40{}`, `40null`, `40{"sid":""}`,
		`40{"sid":1}`, `40{"sid":null}`, `40{"sid":"line\nbreak"}`, `40{"sid":"a"} trailing`,
		`40/admin,{"sid":"wrong-namespace"}`, `42["message",{"type":"auth","result":1}]`,
		`40{"sid":"` + strings.Repeat("a", 1025) + `"}`} {
		if classicNamespaceAccepted([]byte(response)) {
			t.Fatal("invalid namespace acknowledgment accepted")
		}
	}
}

func TestClassicAuthCandidateRejectsErrorsWithoutRemoteText(t *testing.T) {
	secret := "synthetic-private-token-and-document-url"
	tests := []struct {
		name string
		doc  bool
		data string
		want error
	}{
		{"namespace error", false, `44{"message":"` + secret + `"}`, errClassicNamespaceRejected},
		{"namespace disconnect", false, `41`, errClassicAuthClosed},
		{"engine disconnect", false, `1`, errClassicAuthClosed},
		{"namespace malformed", false, `40{"sid":1}`, errClassicNamespace},
		{"namespace other", false, `40/admin,{"sid":"` + secret + `"}`, errClassicNamespace},
		{"early event", false, `42["message",{"type":"auth","result":1}]`, errClassicNamespace},
		{"document rejected", true, `42["message",{"type":"auth","result":0,"error":"` + secret + `"}]`, errClassicDocumentRejected},
		{"document string result", true, `42["message",{"type":"auth","result":"1"}]`, errClassicAuthPacket},
		{"document missing result", true, `42["message",{"type":"auth"}]`, errClassicAuthPacket},
		{"document null result", true, `42["message",{"type":"auth","result":null}]`, errClassicAuthPacket},
		{"document malformed", true, `42[`, errClassicAuthPacket},
		{"late namespace rejection", true, `44{"message":"` + secret + `"}`, errClassicNamespaceRejected},
		{"late namespace disconnect", true, `41`, errClassicAuthClosed},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			client, peer := localWebSocketPair(t)
			frames := namespaceCandidateFrames(t, peer)
			result := make(chan error, 1)
			session := &DocSession{Conn: client, Info: YandexDocsInfo{Token: "synthetic"}}
			go func() {
				_, err := authenticateClassicSession(context.Background(), session, time.Second)
				result <- err
			}()
			_ = namespaceCandidateRead(t, frames)
			if test.doc {
				_ = peer.WriteMessage(websocket.TextMessage, []byte(`40`))
				namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
			}
			_ = peer.WriteMessage(websocket.TextMessage, []byte(test.data))
			select {
			case err := <-result:
				if !errors.Is(err, test.want) || strings.Contains(err.Error(), secret) {
					t.Fatal("rejection did not preserve fixed error category")
				}
			case <-time.After(time.Second):
				t.Fatal("rejected auth did not stop promptly")
			}
			select {
			case _, open := <-frames:
				if open {
					t.Fatal("rejected connection sent additional application data")
				}
			case <-time.After(time.Second):
				t.Fatal("rejected connection remained open")
			}
		})
	}
}

func TestClassicAuthCandidateCancellationClosesBothWaits(t *testing.T) {
	for _, document := range []bool{false, true} {
		t.Run(fmt.Sprint(document), func(t *testing.T) {
			client, peer := localWebSocketPair(t)
			frames := namespaceCandidateFrames(t, peer)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			result := make(chan error, 1)
			go func() {
				_, err := authenticateClassicSession(ctx, &DocSession{Conn: client}, time.Second)
				result <- err
			}()
			_ = namespaceCandidateRead(t, frames)
			if document {
				_ = peer.WriteMessage(websocket.TextMessage, []byte(`40`))
				namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
			}
			cancel()
			select {
			case err := <-result:
				if !errors.Is(err, context.Canceled) {
					t.Fatal("cancellation did not preserve context.Canceled")
				}
			case <-time.After(time.Second):
				t.Fatal("cancelled handshake remained running")
			}
		})
	}
}

func TestClassicAuthCandidateDeadlineClosesBothWaits(t *testing.T) {
	for _, document := range []bool{false, true} {
		t.Run(fmt.Sprint(document), func(t *testing.T) {
			client, peer := localWebSocketPair(t)
			frames := namespaceCandidateFrames(t, peer)
			result := make(chan error, 1)
			started := time.Now()
			go func() {
				_, err := authenticateClassicSession(context.Background(), &DocSession{Conn: client}, 70*time.Millisecond)
				result <- err
			}()
			_ = namespaceCandidateRead(t, frames)
			if document {
				_ = peer.WriteMessage(websocket.TextMessage, []byte(`40`))
				namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
			}
			select {
			case err := <-result:
				if !errors.Is(err, context.DeadlineExceeded) || err != errClassicAuthTimeout || time.Since(started) >= time.Second {
					t.Fatal("handshake deadline was not bounded or stage was lost")
				}
			case <-time.After(time.Second):
				t.Fatal("handshake deadline did not close socket")
			}
		})
	}
}

func TestClassicAuthCandidateBoundsControlPacketFlood(t *testing.T) {
	client, peer := localWebSocketPair(t)
	frames := namespaceCandidateFrames(t, peer)
	result := make(chan error, 1)
	go func() {
		_, err := authenticateClassicSession(context.Background(), &DocSession{Conn: client}, time.Second)
		result <- err
	}()
	_ = namespaceCandidateRead(t, frames)
	for i := 0; i < classicAuthMaxPackets; i++ {
		if err := peer.WriteMessage(websocket.TextMessage, []byte("6")); err != nil {
			break
		}
	}
	select {
	case err := <-result:
		if err != errClassicAuthLimit {
			t.Fatal("control packet budget was not enforced")
		}
	case <-time.After(time.Second):
		t.Fatal("control packet flood waited for deadline")
	}
}

func TestClassicAuthCandidateBoundsPacketAndAggregateBytes(t *testing.T) {
	for _, aggregate := range []bool{false, true} {
		t.Run(fmt.Sprint(aggregate), func(t *testing.T) {
			client, peer := localWebSocketPair(t)
			frames := namespaceCandidateFrames(t, peer)
			result := make(chan error, 1)
			go func() {
				_, err := authenticateClassicSession(context.Background(), &DocSession{Conn: client}, time.Second)
				result <- err
			}()
			_ = namespaceCandidateRead(t, frames)
			if aggregate {
				_ = peer.WriteMessage(websocket.TextMessage, []byte(`40`))
				namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
				packet := []byte(`42["message",{"type":"cursor","cursor":"` + strings.Repeat("a", classicAuthMaxBytes/2) + `"}]`)
				_ = peer.WriteMessage(websocket.TextMessage, packet)
				_ = peer.WriteMessage(websocket.TextMessage, packet)
			} else {
				_ = peer.WriteMessage(websocket.TextMessage, []byte(strings.Repeat("a", classicAuthMaxPacket+1)))
			}
			select {
			case err := <-result:
				if aggregate && err != errClassicAuthLimit || !aggregate && err != errClassicAuthClosed {
					t.Fatal("authentication byte budget was not enforced")
				}
			case <-time.After(time.Second):
				t.Fatal("oversized authentication data remained active")
			}
		})
	}
}

func TestClassicAuthCandidateReplaysEarlyPeerDataOnlyAfterAcceptance(t *testing.T) {
	client, peer := localWebSocketPair(t)
	frames := namespaceCandidateFrames(t, peer)
	tr := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	_ = tr.BaseTransport.Start()
	defer tr.Stop()
	seen := make(chan bool, 1)
	tr.Receive(func(data []byte) { seen <- tr.IsConnected() && string(data) == "early-peer-data" })
	result := make(chan error, 1)
	go func() { result <- tr.activateSession(&DocSession{Conn: client}) }()
	_ = namespaceCandidateRead(t, frames)
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`40`))
	namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
	packet, _ := json.Marshal([]interface{}{"message", map[string]string{"type": "cursor", "cursor": "18;" + base64.StdEncoding.EncodeToString([]byte("early-peer-data"))}})
	_ = peer.WriteMessage(websocket.TextMessage, append([]byte("42"), packet...))
	select {
	case <-seen:
		t.Fatal("peer data delivered before document authentication")
	case <-time.After(20 * time.Millisecond):
	}
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`))
	_ = namespaceCandidateRead(t, frames)
	select {
	case err := <-result:
		if err != nil {
			t.Fatal("authenticated early-peer replay failed")
		}
	case <-time.After(time.Second):
		t.Fatal("activation did not finish")
	}
	select {
	case good := <-seen:
		if !good {
			t.Fatal("early peer data was changed or delivered while disconnected")
		}
	case <-time.After(time.Second):
		t.Fatal("early peer data was dropped")
	}
}

func TestClassicAuthCandidateStopCannotPublishLateAcceptance(t *testing.T) {
	client, peer := localWebSocketPair(t)
	frames := namespaceCandidateFrames(t, peer)
	tr := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	_ = tr.BaseTransport.Start()
	defer tr.Stop()
	result := make(chan error, 1)
	go func() { result <- tr.activateSession(&DocSession{Conn: client}) }()
	_ = namespaceCandidateRead(t, frames)
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`40`))
	namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
	_ = tr.Stop()
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`))
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("stopped transport accepted a late authentication response")
		}
	case <-time.After(time.Second):
		t.Fatal("Stop did not release a document authentication wait")
	}
	tr.Mu.RLock()
	current := tr.session
	tr.Mu.RUnlock()
	if current != nil || tr.IsConnected() || tr.IsRunning() {
		t.Fatal("late authentication resurrected a stopped session")
	}
}

func TestClassicAuthCandidateSuccessfulSocketRemainsUsable(t *testing.T) {
	client, peer := localWebSocketPair(t)
	frames := namespaceCandidateFrames(t, peer)
	result := make(chan error, 1)
	go func() {
		_, err := authenticateClassicSession(context.Background(), &DocSession{Conn: client}, 300*time.Millisecond)
		result <- err
	}()
	_ = namespaceCandidateRead(t, frames)
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`40{"sid":"opaque+sid/="}`))
	namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`))
	_ = namespaceCandidateRead(t, frames)
	select {
	case err := <-result:
		if err != nil {
			t.Fatal("synthetic document authentication failed")
		}
	case <-time.After(time.Second):
		t.Fatal("synthetic authentication did not finish")
	}
	time.Sleep(320 * time.Millisecond)
	if err := client.WriteMessage(websocket.TextMessage, []byte("late-packet")); err != nil {
		t.Fatal("successful authentication retained its deadline or cancellation handler")
	}
	if namespaceCandidateRead(t, frames) != "late-packet" {
		t.Fatal("successful authentication changed later application traffic")
	}
	read := make(chan bool, 1)
	go func() {
		_, message, err := client.ReadMessage()
		read <- err == nil && len(message) == classicAuthMaxPacket+1
	}()
	if err := peer.WriteMessage(websocket.TextMessage, []byte(strings.Repeat("a", classicAuthMaxPacket+1))); err != nil {
		t.Fatal("accepted session could not receive a normal larger batch frame")
	}
	select {
	case good := <-read:
		if !good {
			t.Fatal("authentication limits leaked into the live transport")
		}
	case <-time.After(time.Second):
		t.Fatal("live transport did not receive larger batch frame")
	}
}
