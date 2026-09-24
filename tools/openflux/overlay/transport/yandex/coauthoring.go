// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"bytes"
	"encoding/json"

	"github.com/gorilla/websocket"
)

func (t *YandexDocsTransport) acknowledgeCoAuthoring(session *DocSession, data []byte) bool {
	if !bytes.HasPrefix(data, []byte("42[")) {
		return false
	}
	var event []json.RawMessage
	var name string
	var message struct {
		Type     string `json:"type"`
		Result   int    `json:"result"`
		WaitAuth bool   `json:"waitAuth"`
	}
	if json.Unmarshal(data[2:], &event) != nil || len(event) != 2 ||
		json.Unmarshal(event[0], &name) != nil || name != "message" ||
		json.Unmarshal(event[1], &message) != nil {
		return false
	}
	if !(message.Type == "auth" && message.Result == 1 || message.Type == "connectState" && message.WaitAuth) {
		return false
	}
	t.Mu.RLock()
	current := session != nil && session.Conn != nil && t.session == session && t.IsRunning() && t.IsConnected()
	t.Mu.RUnlock()
	if !current {
		return true
	}
	// Release only this connection's co-authoring auth lock. Never save, delete
	// document changes or release editing locks when acknowledging another peer.
	ack := []byte(`42["message",{"type":"unLockDocument","unlock":true,"isSave":false,"releaseLocks":false}]`)
	if err := session.safeWrite(websocket.TextMessage, ack); err != nil {
		t.disconnectSession(session)
	}
	return true
}
