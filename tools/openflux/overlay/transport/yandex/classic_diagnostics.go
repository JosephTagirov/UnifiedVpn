// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"

	"github.com/gorilla/websocket"
)

type classicTransportStage uint32

const (
	classicDocumentResolved classicTransportStage = iota
	classicDocumentFailed
	classicWebSocketConnected
	classicWebSocketFailed
	classicAuthSent
	classicAuthFailed
	classicAuthAccepted
	classicAuthRejected
	classicConnectionClosed
	classicCloseNoStatus
	classicCloseAbnormal
	classicNamespaceResponse
	classicNamespaceRejected
	classicAuthTimedOut
	classicAuthenticationClosed
	classicStageCount
)

func (stage classicTransportStage) marker() string {
	switch stage {
	case classicDocumentResolved:
		return "document_resolved"
	case classicDocumentFailed:
		return "document_failed"
	case classicWebSocketConnected:
		return "websocket_connected"
	case classicWebSocketFailed:
		return "websocket_failed"
	case classicAuthSent:
		return "auth_sent"
	case classicAuthFailed:
		return "auth_failed"
	case classicAuthAccepted:
		return "auth_accepted"
	case classicAuthRejected:
		return "auth_rejected"
	case classicConnectionClosed:
		return "connection_closed"
	case classicCloseNoStatus:
		return "close_no_status"
	case classicCloseAbnormal:
		return "close_abnormal"
	case classicNamespaceResponse:
		return "namespace_response"
	case classicNamespaceRejected:
		return "namespace_rejected"
	case classicAuthTimedOut:
		return "auth_timeout"
	case classicAuthenticationClosed:
		return "auth_closed"
	default:
		return ""
	}
}

// One attempt emits each fixed marker at most once, regardless of how many
// document messages arrive. No remote text is accepted by this observer.
type classicAttemptDiagnostics struct {
	seen atomic.Uint32
	emit func(classicTransportStage)
}

func (d *classicAttemptDiagnostics) record(stage classicTransportStage) {
	if d == nil || stage >= classicStageCount {
		return
	}
	bit := uint32(1) << stage
	for {
		previous := d.seen.Load()
		if previous&bit != 0 {
			return
		}
		if d.seen.CompareAndSwap(previous, previous|bit) {
			break
		}
	}
	if d.emit != nil {
		d.emit(stage)
		return
	}
	fmt.Println("OPENFLUX_YANDEX_STAGE " + stage.marker())
}

func (d *classicAttemptDiagnostics) closed(err error) {
	d.record(classicConnectionClosed)
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) {
		return
	}
	switch closeError.Code {
	case websocket.CloseNoStatusReceived:
		d.record(classicCloseNoStatus)
	case websocket.CloseAbnormalClosure:
		d.record(classicCloseAbnormal)
	}
}

func (d *classicAttemptDiagnostics) authenticationFailed(err error) {
	if err == nil {
		return
	}
	d.record(classicAuthFailed)
	switch {
	case errors.Is(err, errClassicNamespace):
		d.record(classicNamespaceResponse)
	case errors.Is(err, errClassicNamespaceRejected):
		d.record(classicNamespaceRejected)
	case errors.Is(err, errClassicAuthTimeout), errors.Is(err, context.DeadlineExceeded):
		d.record(classicAuthTimedOut)
	case errors.Is(err, errClassicAuthClosed), errors.Is(err, context.Canceled):
		d.record(classicAuthenticationClosed)
	case errors.Is(err, errClassicDocumentRejected):
		d.record(classicAuthRejected)
	}
}
