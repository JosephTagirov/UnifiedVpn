// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"openflux/transport"
)

func TestClassicDiagnosticMarkersAreBoundedAndFixed(t *testing.T) {
	want := []string{
		"document_resolved", "document_failed", "websocket_connected", "websocket_failed",
		"auth_sent", "auth_failed", "auth_accepted", "auth_rejected", "connection_closed",
		"close_no_status", "close_abnormal",
		"namespace_response", "namespace_rejected", "auth_timeout", "auth_closed",
	}
	if classicStageCount > 32 {
		t.Fatal("diagnostic stages no longer fit the bounded bitmask")
	}
	var got []string
	diagnostics := &classicAttemptDiagnostics{emit: func(stage classicTransportStage) {
		got = append(got, stage.marker())
	}}
	for stage := classicTransportStage(0); stage < classicStageCount; stage++ {
		diagnostics.record(stage)
		diagnostics.record(stage)
	}
	for _, unknown := range []classicTransportStage{classicStageCount, 31, 32, 100, ^classicTransportStage(0)} {
		diagnostics.record(unknown)
		if unknown.marker() != "" {
			t.Fatal("unknown diagnostic stage produced output")
		}
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("unexpected or repeated markers: %v", got)
	}
	var absent *classicAttemptDiagnostics
	absent.record(classicAuthSent)
	absent.closed(errors.New("synthetic secret"))
	absent.authenticationFailed(errClassicNamespace)
}

func TestClassicDiagnosticAuthFailuresUseOnlyFixedErrorCategories(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want []string
	}{
		{"nil", nil, nil},
		{"namespace response", errClassicNamespace, []string{"auth_failed", "namespace_response"}},
		{"namespace rejected", errClassicNamespaceRejected, []string{"auth_failed", "namespace_rejected"}},
		{"document rejected", errClassicDocumentRejected, []string{"auth_failed", "auth_rejected"}},
		{"timeout", errClassicAuthTimeout, []string{"auth_failed", "auth_timeout"}},
		{"context timeout", context.DeadlineExceeded, []string{"auth_failed", "auth_timeout"}},
		{"closed", errClassicAuthClosed, []string{"auth_failed", "auth_closed"}},
		{"canceled", context.Canceled, []string{"auth_failed", "auth_closed"}},
		{"packet", errClassicAuthPacket, []string{"auth_failed"}},
		{"limit", errClassicAuthLimit, []string{"auth_failed"}},
		{"write", errClassicAuthWrite, []string{"auth_failed"}},
		{"remote text", errors.New("synthetic-secret namespace_rejected auth_timeout auth_closed"), []string{"auth_failed"}},
		{"spoofed sentinel", errors.New(errClassicNamespace.Error()), []string{"auth_failed"}},
	}
	for _, test := range tests {
		for _, wrapped := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/wrapped=%t", test.name, wrapped), func(t *testing.T) {
				var got []string
				diagnostics := &classicAttemptDiagnostics{emit: func(stage classicTransportStage) {
					got = append(got, stage.marker())
				}}
				err := test.err
				if wrapped && err != nil {
					err = fmt.Errorf("synthetic-secret remote detail: %w", err)
				}
				diagnostics.authenticationFailed(err)
				diagnostics.authenticationFailed(err)
				if !reflect.DeepEqual(got, test.want) {
					t.Fatalf("unexpected failure category: %v", got)
				}
			})
		}
	}
}

func TestClassicDiagnosticMarkersDeduplicateConcurrentMessages(t *testing.T) {
	var mu sync.Mutex
	counts := make(map[classicTransportStage]int)
	diagnostics := &classicAttemptDiagnostics{emit: func(stage classicTransportStage) {
		mu.Lock()
		counts[stage]++
		mu.Unlock()
	}}
	var workers sync.WaitGroup
	for i := 0; i < 40; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for stage := classicTransportStage(0); stage < classicStageCount; stage++ {
				diagnostics.record(stage)
			}
		}()
	}
	workers.Wait()
	for stage := classicTransportStage(0); stage < classicStageCount; stage++ {
		if counts[stage] != 1 {
			t.Fatalf("stage %s occurred %d times", stage.marker(), counts[stage])
		}
	}
}

func TestClassicDiagnosticAuthRequiresActualServerResult(t *testing.T) {
	tests := []struct {
		name, frame string
		want        []classicTransportStage
	}{
		{"accepted", `42["message",{"type":"auth","result":1}]`, []classicTransportStage{classicAuthAccepted}},
		{"rejected zero", `42["message",{"type":"auth","result":0}]`, []classicTransportStage{classicAuthRejected}},
		{"rejected negative", `42["message",{"type":"auth","result":-1}]`, []classicTransportStage{classicAuthRejected}},
		{"rejected other", `42["message",{"type":"auth","result":2,"error":"synthetic secret"}]`, []classicTransportStage{classicAuthRejected}},
		{"missing result", `42["message",{"type":"auth"}]`, nil},
		{"null result", `42["message",{"type":"auth","result":null}]`, nil},
		{"string result", `42["message",{"type":"auth","result":"1"}]`, nil},
		{"boolean result", `42["message",{"type":"auth","result":true}]`, nil},
		{"noninteger result", `42["message",{"type":"auth","result":1.5}]`, nil},
		{"connect state is not acceptance", `42["message",{"type":"connectState","waitAuth":true,"result":1}]`, nil},
		{"socketio ack is not acceptance", `40{"sid":"synthetic-secret"}`, nil},
		{"nested auth is not acceptance", `42["message",{"type":"cursor","nested":{"type":"auth","result":1}}]`, nil},
		{"other event", `42["other",{"type":"auth","result":1}]`, nil},
		{"wrong array length", `42["message",{"type":"auth","result":1},{}]`, nil},
		{"trailing text", `42["message",{"type":"auth","result":1}] trailing`, nil},
		{"malformed", `42[`, nil},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			client, _ := localWebSocketPair(t)
			tp := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
			tp.BaseTransport.Start()
			defer tp.Stop()
			var got []classicTransportStage
			session := &DocSession{Conn: client, diagnostics: &classicAttemptDiagnostics{
				emit: func(stage classicTransportStage) { got = append(got, stage) },
			}}
			tp.session = session
			tp.SetConnected(true)
			tp.handleMessage(session, []byte(test.frame))
			tp.handleMessage(session, []byte(test.frame))
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("got diagnostic stages %v, want %v", got, test.want)
			}
			if !tp.IsConnected() {
				t.Fatal("diagnostic observer changed existing session state")
			}
		})
	}
}

func TestClassicDiagnosticStaleOrStoppedSessionCannotReportAuth(t *testing.T) {
	for _, stopped := range []bool{false, true} {
		t.Run(fmt.Sprint(stopped), func(t *testing.T) {
			client, _ := localWebSocketPair(t)
			tp := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
			tp.BaseTransport.Start()
			defer tp.Stop()
			called := false
			session := &DocSession{Conn: client, diagnostics: &classicAttemptDiagnostics{
				emit: func(classicTransportStage) { called = true },
			}}
			tp.session = &DocSession{}
			tp.SetConnected(true)
			if stopped {
				tp.session = session
				tp.BaseTransport.Stop()
			}
			tp.handleMessage(session, []byte(`42["message",{"type":"auth","result":1}]`))
			tp.handleMessage(session, []byte(`42["message",{"type":"auth","result":0}]`))
			if called {
				t.Fatal("inactive session reported an authentication result")
			}
		})
	}
}

func TestClassicDiagnosticAuthSentDoesNotClaimAcceptance(t *testing.T) {
	client, peer := localWebSocketPair(t)
	frames := namespaceCandidateFrames(t, peer)
	tp := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	tp.BaseTransport.Start()
	defer tp.Stop()
	stages := make(chan classicTransportStage, 4)
	session := &DocSession{Conn: client, UserID: "synthetic-user",
		Info:        YandexDocsInfo{Token: "synthetic-token", DocID: "synthetic-document"},
		diagnostics: &classicAttemptDiagnostics{emit: func(stage classicTransportStage) { stages <- stage }},
	}
	result := make(chan error, 1)
	go func() { result <- tp.activateSession(session) }()
	_ = namespaceCandidateRead(t, frames)
	_ = peer.WriteMessage(websocket.TextMessage, []byte("40"))
	namespaceCandidateRequireAuth(t, namespaceCandidateRead(t, frames))
	select {
	case stage := <-stages:
		if stage != classicAuthSent {
			t.Fatal("authentication not classified as sent")
		}
	case <-time.After(time.Second):
		t.Fatal("sent marker missing")
	}
	select {
	case <-stages:
		t.Fatal("sending authentication claimed server acceptance")
	case <-time.After(20 * time.Millisecond):
	}
	_ = peer.WriteMessage(websocket.TextMessage, []byte(`42["message",{"type":"auth","result":1}]`))
	_ = namespaceCandidateRead(t, frames)
	select {
	case err := <-result:
		if err != nil {
			t.Fatal("local activation failed")
		}
	case <-time.After(time.Second):
		t.Fatal("local activation did not finish")
	}
	select {
	case stage := <-stages:
		if stage != classicAuthAccepted {
			t.Fatal("server acceptance marker missing")
		}
	case <-time.After(time.Second):
		t.Fatal("server acceptance marker missing")
	}
}

func TestClassicDiagnosticCloseNeverEmitsRemoteReason(t *testing.T) {
	secret := strings.Repeat("synthetic-private-url-cookie-token\n", 1024)
	for _, test := range []struct {
		name string
		err  error
		want []classicTransportStage
	}{
		{"no status", &websocket.CloseError{Code: 1005, Text: secret}, []classicTransportStage{classicConnectionClosed, classicCloseNoStatus}},
		{"wrapped abnormal", fmt.Errorf("%s: %w", secret, &websocket.CloseError{Code: 1006, Text: secret}), []classicTransportStage{classicConnectionClosed, classicCloseAbnormal}},
		{"other close", &websocket.CloseError{Code: 1008, Text: secret}, []classicTransportStage{classicConnectionClosed}},
		{"untrusted number", &websocket.CloseError{Code: -99999, Text: secret}, []classicTransportStage{classicConnectionClosed}},
		{"io error", errors.New(secret), []classicTransportStage{classicConnectionClosed}},
		{"nil", nil, []classicTransportStage{classicConnectionClosed}},
	} {
		t.Run(test.name, func(t *testing.T) {
			var got []classicTransportStage
			diagnostics := &classicAttemptDiagnostics{emit: func(stage classicTransportStage) {
				got = append(got, stage)
			}}
			diagnostics.closed(test.err)
			diagnostics.closed(test.err)
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("unexpected close categories: %v", got)
			}
		})
	}
}
