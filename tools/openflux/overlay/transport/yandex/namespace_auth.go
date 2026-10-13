// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gorilla/websocket"
)

const (
	classicAuthTimeout    = 15 * time.Second
	classicAuthMaxPacket  = 1 << 20
	classicAuthMaxPackets = 64
	classicAuthMaxBytes   = 1 << 20
)

var (
	errClassicNamespace         = errors.New("Yandex session authentication failed (stage=namespace_response)")
	errClassicNamespaceRejected = errors.New("Yandex session authentication failed (stage=namespace_rejected)")
	errClassicDocumentRejected  = errors.New("Yandex session authentication failed (stage=document_rejected)")
	errClassicAuthClosed        = errors.New("Yandex session authentication failed (stage=session_closed)")
	errClassicAuthPacket        = errors.New("Yandex session authentication failed (stage=auth_response)")
	errClassicAuthLimit         = errors.New("Yandex session authentication failed (stage=auth_limit)")
	errClassicAuthWrite         = errors.New("Yandex session authentication failed (stage=auth_write)")
	errClassicAuthTimeout       = fmt.Errorf("Yandex session authentication failed (stage=auth_timeout): %w", context.DeadlineExceeded)
)

type classicAuthReader struct {
	ctx     context.Context
	session *DocSession
	packets int
	bytes   int
}

func (r *classicAuthReader) read() ([]byte, error) {
	for r.packets < classicAuthMaxPackets {
		if err := r.ctx.Err(); err != nil {
			return nil, classicAuthContextError(r.ctx, errClassicAuthPacket)
		}
		kind, packet, err := r.session.Conn.ReadMessage()
		if err != nil {
			r.session.diagnostics.closed(err)
			var timeout net.Error
			if errors.As(err, &timeout) && timeout.Timeout() {
				return nil, classicAuthContextError(r.ctx, errClassicAuthTimeout)
			}
			return nil, classicAuthContextError(r.ctx, errClassicAuthClosed)
		}
		r.packets++
		r.bytes += len(packet)
		if kind != websocket.TextMessage || !utf8.Valid(packet) {
			return nil, errClassicAuthPacket
		}
		if r.bytes > classicAuthMaxBytes {
			return nil, errClassicAuthLimit
		}
		switch string(packet) {
		case "2":
			if r.session.safeWrite(websocket.TextMessage, []byte("3")) != nil {
				return nil, classicAuthContextError(r.ctx, errClassicAuthWrite)
			}
		case "3", "6":
		case "1":
			return nil, errClassicAuthClosed
		default:
			if bytes.HasPrefix(packet, []byte("44")) {
				return nil, errClassicNamespaceRejected
			}
			if bytes.HasPrefix(packet, []byte("41")) {
				return nil, errClassicAuthClosed
			}
			return packet, nil
		}
	}
	return nil, errClassicAuthLimit
}

func classicNamespaceAccepted(packet []byte) bool {
	// Legacy Socket.IO acknowledges the root namespace with bare 40. Newer
	// peers include an opaque SID, which is not limited to URL-safe characters.
	if bytes.Equal(packet, []byte("40")) {
		return true
	}
	if !bytes.HasPrefix(packet, []byte("40{")) || len(packet) > classicAuthMaxPacket {
		return false
	}
	var response struct {
		SID string `json:"sid"`
	}
	if json.Unmarshal(packet[2:], &response) != nil || len(response.SID) == 0 || len(response.SID) > 1024 || !utf8.ValidString(response.SID) {
		return false
	}
	for _, character := range response.SID {
		if unicode.IsControl(character) {
			return false
		}
	}
	return true
}

// Neither the session nor the persistent writer is published until both
// protocol layers accept it. Early peer packets are bounded and replayed only
// after authentication; waitAuth acknowledgments remain possible while waiting.
func authenticateClassicSession(parent context.Context, session *DocSession, timeout time.Duration) (pending [][]byte, result error) {
	if parent == nil || session == nil || session.Conn == nil || timeout <= 0 {
		return nil, errClassicAuthPacket
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	stopCancel := context.AfterFunc(ctx, func() { _ = session.Conn.Close() })
	defer stopCancel()
	defer func() {
		if result != nil {
			_ = session.Conn.Close()
		}
	}()
	if ctx.Err() != nil {
		return nil, classicAuthContextError(ctx, errClassicAuthClosed)
	}
	deadline, _ := ctx.Deadline()
	session.Conn.SetReadLimit(classicAuthMaxPacket)
	if session.Conn.SetReadDeadline(deadline) != nil || session.Conn.SetWriteDeadline(deadline) != nil {
		return nil, classicAuthContextError(ctx, errClassicAuthClosed)
	}
	authToken, _ := json.Marshal(map[string]string{"token": session.Info.Token})
	if session.safeWrite(websocket.TextMessage, append([]byte("40"), authToken...)) != nil {
		return nil, classicAuthContextError(ctx, errClassicAuthWrite)
	}
	reader := classicAuthReader{ctx: ctx, session: session}
	packet, err := reader.read()
	if err != nil {
		return nil, err
	}
	if !classicNamespaceAccepted(packet) {
		return nil, errClassicNamespace
	}
	authData := map[string]interface{}{
		"type": "auth", "docid": session.Info.DocID, "token": "fghhfgsjdgfjs",
		"user": map[string]interface{}{"id": session.UserID}, "editorType": 0,
		"lastOtherSaveTime": -1, "permissions": session.Info.Permissions,
		"openCmd": session.Info.OpenCmd, "coEditingMode": "fast", "jwtOpen": session.Info.Token,
	}
	message, err := json.Marshal([]interface{}{"message", authData})
	if err != nil || session.safeWrite(websocket.TextMessage, append([]byte("42"), message...)) != nil {
		return nil, classicAuthContextError(ctx, errClassicAuthWrite)
	}
	session.diagnostics.record(classicAuthSent)
	for {
		packet, err := reader.read()
		if err != nil {
			return nil, err
		}
		reply, ok := parseClassicCoauthoring(packet)
		if !ok {
			return nil, errClassicAuthPacket
		}
		if reply.Type == "auth" {
			if reply.Result == nil {
				return nil, errClassicAuthPacket
			}
			if *reply.Result != 1 {
				session.diagnostics.record(classicAuthRejected)
				return nil, errClassicDocumentRejected
			}
			session.diagnostics.record(classicAuthAccepted)
			if acknowledgeClassicDocumentLock(session) != nil {
				return nil, classicAuthContextError(ctx, errClassicAuthWrite)
			}
			break
		}
		if reply.Type == "connectState" && reply.WaitAuth {
			if acknowledgeClassicDocumentLock(session) != nil {
				return nil, classicAuthContextError(ctx, errClassicAuthWrite)
			}
			continue
		}
		pending = append(pending, packet)
	}
	if err := ctx.Err(); err != nil {
		return nil, classicAuthContextError(ctx, errClassicAuthClosed)
	}
	if session.Conn.SetReadDeadline(time.Time{}) != nil || session.Conn.SetWriteDeadline(time.Time{}) != nil {
		return nil, classicAuthContextError(ctx, errClassicAuthClosed)
	}
	session.Conn.SetReadLimit(0)
	if !stopCancel() {
		return nil, classicAuthContextError(ctx, errClassicAuthClosed)
	}
	return pending, nil
}

func classicAuthContextError(ctx context.Context, fallback error) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return errClassicAuthTimeout
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		return context.Canceled
	}
	return fallback
}
