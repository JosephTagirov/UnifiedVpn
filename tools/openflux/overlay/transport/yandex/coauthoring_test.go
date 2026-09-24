// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"encoding/json"
	"net"
	"reflect"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"openflux/transport"
)

func TestCoauthoringAcknowledgesOwnSessionWithoutDocumentChanges(t *testing.T) {
	for _, input := range []string{
		`42["message",{"type":"auth","result":1}]`,
		`42["message",{"type":"connectState","waitAuth":true}]`,
	} {
		t.Run(input, func(t *testing.T) {
			client, peer := localWebSocketPair(t)
			tp := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
			tp.BaseTransport.Start()
			session := &DocSession{Conn: client}
			tp.session = session
			tp.SetConnected(true)
			defer tp.Stop()
			tp.handleMessage(session, []byte(input))
			peer.SetReadDeadline(time.Now().Add(time.Second))
			kind, message, err := peer.ReadMessage()
			if err != nil || kind != websocket.TextMessage || len(message) < 2 || string(message[:2]) != "42" {
				t.Fatal("co-authoring acknowledgement missing")
			}
			var event []json.RawMessage
			var name string
			var options map[string]any
			if json.Unmarshal(message[2:], &event) != nil || len(event) != 2 ||
				json.Unmarshal(event[0], &name) != nil || name != "message" || json.Unmarshal(event[1], &options) != nil {
				t.Fatal("invalid co-authoring acknowledgement")
			}
			want := map[string]any{"type": "unLockDocument", "unlock": true, "isSave": false, "releaseLocks": false}
			if !reflect.DeepEqual(options, want) {
				t.Fatal("acknowledgement must not save, delete changes, or release editing locks")
			}
		})
	}
}

func TestCoauthoringIgnoresOtherEventsAndUnsuccessfulAuth(t *testing.T) {
	client, peer := localWebSocketPair(t)
	tp := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	tp.BaseTransport.Start()
	session := &DocSession{Conn: client}
	tp.session = session
	tp.SetConnected(true)
	defer tp.Stop()
	for _, input := range []string{
		`42["message",{"type":"auth","result":0}]`,
		`42["message",{"type":"auth"}]`,
		`42["message",{"type":"auth","result":"1"}]`,
		`42["message",{"type":"connectState","waitAuth":false}]`,
		`42["message",{"type":"connectState"}]`,
		`42["message",{"type":"connectState","waitAuth":"true"}]`,
		`42["other",{"type":"connectState","waitAuth":true}]`,
		`42["message",{"type":"connectState","waitAuth":true},{}]`,
		`42["message",{"type":"message","nested":{"type":"auth","result":1}}]`,
		`42["message",{"type":"waitAuth"}]`,
		`42["message",{"type":"connectState","waitAuth":true}] trailing`,
		`42[`,
	} {
		tp.handleMessage(session, []byte(input))
	}
	peer.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
	_, _, err := peer.ReadMessage()
	if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
		t.Fatal("unrelated or malformed event caused a document command")
	}
}

func TestCoauthoringRejectsStaleAndStoppedSessions(t *testing.T) {
	for _, stopped := range []bool{false, true} {
		client, peer := localWebSocketPair(t)
		tp := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
		tp.BaseTransport.Start()
		session := &DocSession{Conn: client}
		if stopped {
			tp.session = session
			tp.BaseTransport.Stop()
		} else {
			tp.session = &DocSession{}
		}
		tp.handleMessage(session, []byte(`42["message",{"type":"connectState","waitAuth":true}]`))
		peer.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
		_, _, err := peer.ReadMessage()
		if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
			t.Fatal("inactive session caused a document command")
		}
		tp.Stop()
	}
}
