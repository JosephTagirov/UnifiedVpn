// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"bytes"
	"encoding/json"

	"github.com/gorilla/websocket"
)

type classicCoauthoringMessage struct {
	Type     string `json:"type"`
	Result   *int   `json:"result"`
	WaitAuth bool   `json:"waitAuth"`
}

func parseClassicCoauthoring(data []byte) (classicCoauthoringMessage, bool) {
	var message classicCoauthoringMessage
	if !bytes.HasPrefix(data, []byte("42[")) {
		return message, false
	}
	var event []json.RawMessage
	var name string
	if json.Unmarshal(data[2:], &event) != nil || len(event) != 2 ||
		json.Unmarshal(event[0], &name) != nil || name != "message" ||
		json.Unmarshal(event[1], &message) != nil {
		return message, false
	}
	return message, true
}

func acknowledgeClassicDocumentLock(session *DocSession) error {
	// Never save, delete document changes, or release editing locks.
	ack := []byte(`42["message",{"type":"unLockDocument","unlock":true,"isSave":false,"releaseLocks":false}]`)
	return session.safeWrite(websocket.TextMessage, ack)
}

func (t *YandexDocsTransport) acknowledgeCoAuthoring(session *DocSession, data []byte) bool {
	message, ok := parseClassicCoauthoring(data)
	if !ok {
		return false
	}
	authAccepted := message.Type == "auth" && message.Result != nil && *message.Result == 1
	if message.Type == "auth" && message.Result != nil {
		t.Mu.RLock()
		current := session != nil && session.Conn != nil && t.session == session && t.IsRunning() && t.IsConnected()
		t.Mu.RUnlock()
		if current {
			if authAccepted {
				session.diagnostics.record(classicAuthAccepted)
			} else {
				session.diagnostics.record(classicAuthRejected)
			}
		}
	}
	if !(authAccepted || message.Type == "connectState" && message.WaitAuth) {
		return false
	}
	t.Mu.RLock()
	current := session != nil && session.Conn != nil && t.session == session && t.IsRunning() && t.IsConnected()
	t.Mu.RUnlock()
	if !current {
		return true
	}
	if err := acknowledgeClassicDocumentLock(session); err != nil {
		t.disconnectSession(session)
	}
	return true
}
