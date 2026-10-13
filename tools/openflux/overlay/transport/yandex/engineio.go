// SPDX-License-Identifier: GPL-3.0-or-later
// Handshake approach adapted from OpenFlux PR #115, commit
// ee86772554a77c33e3f807167425570739e11bb9 by meepo161.
package yandex

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

const (
	engineIOHandshakeTimeout = 30 * time.Second
	engineIOPollTimeout      = 15 * time.Second
	engineIOSocketTimeout    = 10 * time.Second
	engineIOMaxPacket        = 64 << 10
	engineIOMaxProbePackets  = 8
)

var (
	errEngineIORequest = errors.New("Yandex Engine.IO handshake failed (stage=request)")
	errEngineIOPoll    = errors.New("Yandex Engine.IO handshake failed (stage=polling)")
	errEngineIODial    = errors.New("Yandex Engine.IO handshake failed (stage=websocket)")
	errEngineIOProbe   = errors.New("Yandex Engine.IO handshake failed (stage=upgrade)")
	errEngineIOOpen    = errors.New("Yandex Engine.IO handshake failed (stage=open)")
)

// engineIODial does not send Socket.IO or document packets. Polling-only state
// lives in this attempt; direct fallback uses only the original browser proof.
// The caller owns the returned socket and must close it when its context stops.
func engineIODial(parent context.Context, info YandexDocsInfo, client *http.Client, dialer *websocket.Dialer, timeout time.Duration) (*websocket.Conn, error) {
	if parent == nil || client == nil || dialer == nil || timeout <= 0 {
		return nil, errEngineIORequest
	}
	wsURL, pollURL, err := engineIOURLs(info)
	if err != nil {
		return nil, err
	}
	if !ValidBrowserUserAgent(browserUserAgent(info.BrowserUserAgent)) || len(info.CookieStr) > 16384 {
		return nil, errEngineIORequest
	}
	if info.CookieStr != "" {
		cookies, err := http.ParseCookie(info.CookieStr)
		if err != nil || len(cookies) > 64 {
			return nil, errEngineIORequest
		}
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	sid, cookies, pollErr := engineIOPoll(ctx, info, pollURL, client)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if pollErr == nil {
		query := wsURL.Query()
		query.Set("sid", sid)
		wsURL.RawQuery = query.Encode()
	} else {
		cookies = info.CookieStr
	}
	headers := make(http.Header)
	headers.Set("User-Agent", browserUserAgent(info.BrowserUserAgent))
	headers.Set("Origin", info.Origin)
	if cookies != "" {
		headers.Set("Cookie", cookies)
	}
	// A private, per-attempt cookie jar is used for polling above. Do not let a
	// caller-supplied dialer jar add unrelated cookies or persist this session.
	wsDialer := *dialer
	wsDialer.Jar = nil
	conn, response, err := wsDialer.DialContext(ctx, wsURL.String(), headers)
	if err != nil {
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		return nil, engineIOContextError(ctx, errEngineIODial)
	}
	ready := false
	defer func() {
		if !ready {
			_ = conn.Close()
		}
	}()
	stopCancel := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stopCancel()
	deadline := time.Now().Add(engineIOSocketTimeout)
	if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
		deadline = limit
	}
	if conn.SetReadDeadline(deadline) != nil || conn.SetWriteDeadline(deadline) != nil {
		return nil, engineIOContextError(ctx, errEngineIODial)
	}
	conn.SetReadLimit(engineIOMaxPacket)
	if pollErr == nil {
		err = engineIOProbe(conn)
	} else {
		err = engineIOAwaitOpen(conn)
	}
	if err != nil {
		return nil, engineIOContextError(ctx, err)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if conn.SetReadDeadline(time.Time{}) != nil || conn.SetWriteDeadline(time.Time{}) != nil {
		return nil, engineIOContextError(ctx, errEngineIODial)
	}
	// The normal transport accepts larger batch frames after the bounded
	// handshake. Preserve that behavior instead of leaving a 64 KiB read cap.
	conn.SetReadLimit(0)
	if !stopCancel() {
		return nil, engineIOContextError(ctx, errEngineIODial)
	}
	ready = true
	return conn, nil
}

func engineIOURLs(info YandexDocsInfo) (*url.URL, *url.URL, error) {
	ws, err := url.Parse(info.WsURL)
	origin, originErr := url.Parse(info.Origin)
	if err != nil || originErr != nil || len(info.WsURL) > 16384 || len(info.Origin) > 8192 ||
		ws.Scheme != "wss" || ws.Host == "" || ws.User != nil || ws.Fragment != "" ||
		origin.Scheme != "https" || origin.Host == "" || origin.User != nil || origin.Fragment != "" ||
		origin.RawQuery != "" || origin.Path != "" && origin.Path != "/" ||
		!strings.EqualFold(ws.Host, origin.Host) || !strings.EqualFold(info.Host, origin.Host) {
		return nil, nil, errEngineIORequest
	}
	query, err := url.ParseQuery(ws.RawQuery)
	if err != nil || len(query["EIO"]) != 1 || query.Get("EIO") != "4" ||
		len(query["transport"]) != 1 || query.Get("transport") != "websocket" || query.Has("sid") {
		return nil, nil, errEngineIORequest
	}
	poll := *ws
	poll.Scheme = "https"
	query.Set("transport", "polling")
	poll.RawQuery = query.Encode()
	return ws, &poll, nil
}

func engineIOPoll(parent context.Context, info YandexDocsInfo, pollURL *url.URL, base *http.Client) (string, string, error) {
	ctx, cancel := context.WithTimeout(parent, engineIOPollTimeout)
	defer cancel()
	jar, _ := cookiejar.New(nil)
	if info.CookieStr != "" {
		cookies, err := http.ParseCookie(info.CookieStr)
		if err != nil {
			return "", "", errEngineIORequest
		}
		jar.SetCookies(pollURL, cookies)
	}
	client := *base
	client.Jar = jar
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, pollURL.String(), nil)
	if err != nil {
		return "", "", errEngineIORequest
	}
	request.Header.Set("User-Agent", browserUserAgent(info.BrowserUserAgent))
	request.Header.Set("Origin", info.Origin)
	response, err := client.Do(request)
	if err != nil {
		return "", "", engineIOContextError(ctx, errEngineIOPoll)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", "", errEngineIOPoll
	}
	packet, err := io.ReadAll(io.LimitReader(response.Body, engineIOMaxPacket+1))
	if err != nil || len(packet) > engineIOMaxPacket {
		return "", "", engineIOContextError(ctx, errEngineIOPoll)
	}
	sid, upgrades, err := engineIOOpen(packet)
	if err != nil || !upgrades {
		return "", "", errEngineIOPoll
	}
	cookieRequest := &http.Request{Header: make(http.Header)}
	for _, cookie := range jar.Cookies(pollURL) {
		cookieRequest.AddCookie(cookie)
	}
	header := cookieRequest.Header.Get("Cookie")
	if len(header) > 16384 || len(jar.Cookies(pollURL)) > 64 {
		return "", "", errEngineIOPoll
	}
	return sid, header, nil
}

// Engine.IO v4 has a type byte followed by JSON, not the v3 length-prefix
// framing. Reject trailers and non-OPEN packets instead of searching for '{'.
func engineIOOpen(packet []byte) (string, bool, error) {
	if len(packet) < 3 || len(packet) > engineIOMaxPacket || packet[0] != '0' {
		return "", false, errEngineIOOpen
	}
	var open struct {
		SID      string   `json:"sid"`
		Upgrades []string `json:"upgrades"`
	}
	if json.Unmarshal(packet[1:], &open) != nil || !engineIOValidSID(open.SID) {
		return "", false, errEngineIOOpen
	}
	for _, upgrade := range open.Upgrades {
		if upgrade == "websocket" {
			return open.SID, true, nil
		}
	}
	return open.SID, false, nil
}

func engineIOValidSID(sid string) bool {
	if len(sid) == 0 || len(sid) > 128 {
		return false
	}
	for _, c := range sid {
		if c != '-' && c != '_' && (c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

func engineIOProbe(conn *websocket.Conn) error {
	if conn.WriteMessage(websocket.TextMessage, []byte("2probe")) != nil {
		return errEngineIOProbe
	}
	for i := 0; i < engineIOMaxProbePackets; i++ {
		kind, packet, err := conn.ReadMessage()
		if err != nil || kind != websocket.TextMessage {
			return errEngineIOProbe
		}
		switch string(packet) {
		case "3probe":
			if conn.WriteMessage(websocket.TextMessage, []byte("5")) != nil {
				return errEngineIOProbe
			}
			return nil
		case "2":
			if conn.WriteMessage(websocket.TextMessage, []byte("3")) != nil {
				return errEngineIOProbe
			}
		case "6":
		default:
			return errEngineIOProbe
		}
	}
	return errEngineIOProbe
}

func engineIOAwaitOpen(conn *websocket.Conn) error {
	kind, packet, err := conn.ReadMessage()
	if err != nil || kind != websocket.TextMessage {
		return errEngineIOOpen
	}
	_, _, err = engineIOOpen(packet)
	return err
}

func engineIOContextError(ctx context.Context, fallback error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return fallback
}
