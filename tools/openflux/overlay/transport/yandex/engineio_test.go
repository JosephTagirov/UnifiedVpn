// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"openflux/transport"
)

const engineIOTestAgent = "SyntheticBrowser/1.0"

func engineIOTestInfo(server *httptest.Server) YandexDocsInfo {
	u, _ := url.Parse(server.URL)
	return YandexDocsInfo{
		WsURL: "wss://" + u.Host + "/doc/c/?EIO=4&transport=websocket&extra=a%26b",
		Host:  u.Host, Origin: server.URL, BrowserUserAgent: engineIOTestAgent,
		CookieStr: "spravka=synthetic-proof; io=old-session",
	}
}

func engineIOTestDialer(server *httptest.Server) *websocket.Dialer {
	return &websocket.Dialer{
		TLSClientConfig:  server.Client().Transport.(*http.Transport).TLSClientConfig.Clone(),
		HandshakeTimeout: time.Second,
	}
}

func engineIOTestUpgrade(w http.ResponseWriter, r *http.Request) (*websocket.Conn, error) {
	return (&websocket.Upgrader{CheckOrigin: func(request *http.Request) bool {
		u, err := url.Parse(request.Header.Get("Origin"))
		return err == nil && u.Scheme == "https" && u.Host == request.Host
	}}).Upgrade(w, r, nil)
}

func engineIOExpectClosed(t *testing.T, closed <-chan struct{}) {
	t.Helper()
	select {
	case <-closed:
	case <-time.After(2 * time.Second):
		t.Fatal("handshake socket was not closed")
	}
}

func TestEngineIOCandidateStrictPollingUpgradePreservesIdentity(t *testing.T) {
	got := make(chan []string, 1)
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.UserAgent() != engineIOTestAgent || r.URL.Query().Get("extra") != "a&b" ||
			r.Header.Get("Cookie") == "" {
			t.Error("browser identity or existing query was lost")
		}
		if r.URL.Query().Get("transport") == "polling" {
			if r.URL.Query().Has("sid") {
				t.Error("initial poll reused a SID")
			}
			http.SetCookie(w, &http.Cookie{Name: "io", Value: "new-session", Secure: true})
			http.SetCookie(w, &http.Cookie{Name: "foreign", Value: "must-not-send", Domain: "unrelated.invalid"})
			fmt.Fprint(w, `0{"sid":"safe_SID-1","upgrades":["websocket"],"pingInterval":25000}`)
			return
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		defer close(closed)
		if r.URL.Query().Get("sid") != "safe_SID-1" {
			t.Error("WebSocket was not bound to the polling session")
		}
		proof, proofErr := r.Cookie("spravka")
		ioCookie, ioErr := r.Cookie("io")
		if proofErr != nil || proof.Value != "synthetic-proof" || ioErr != nil || ioCookie.Value != "new-session" {
			t.Error("cookies were not preserved and merged by name")
		}
		if _, err := r.Cookie("foreign"); err == nil {
			t.Error("foreign cookie escaped its domain")
		}
		var sequence []string
		for _, reply := range []string{"2", "3probe", ""} {
			_, packet, err := conn.ReadMessage()
			if err != nil {
				return
			}
			sequence = append(sequence, string(packet))
			if reply != "" {
				_ = conn.WriteMessage(websocket.TextMessage, []byte(reply))
			}
		}
		_, packet, err := conn.ReadMessage()
		if err == nil {
			sequence = append(sequence, string(packet))
			got <- sequence
		}
		_, _, _ = conn.ReadMessage()
	}))
	defer server.Close()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteMessage(websocket.TextMessage, []byte(`40{"token":"synthetic"}`)); err != nil {
		t.Fatal("could not send auth after upgrade")
	}
	select {
	case sequence := <-got:
		if strings.Join(sequence, "|") != `2probe|3|5|40{"token":"synthetic"}` {
			t.Fatal("Socket.IO was sent before Engine.IO completed")
		}
	case <-time.After(time.Second):
		t.Fatal("strict handshake did not complete")
	}
	_ = conn.Close()
	engineIOExpectClosed(t, closed)
}

func TestEngineIOCandidateDirectFallbackWaitsForOpen(t *testing.T) {
	var early atomic.Bool
	got := make(chan string, 1)
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			http.NotFound(w, r)
			return
		}
		if r.URL.Query().Has("sid") {
			t.Error("fallback retained a failed polling SID")
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		defer close(closed)
		frames := make(chan string, 1)
		go func() {
			_, packet, err := conn.ReadMessage()
			if err == nil {
				frames <- string(packet)
			}
		}()
		select {
		case <-frames:
			early.Store(true)
			return
		case <-time.After(50 * time.Millisecond):
		}
		_ = conn.WriteMessage(websocket.TextMessage, []byte(`0{"sid":"direct"}`))
		select {
		case packet := <-frames:
			got <- packet
		case <-time.After(time.Second):
		}
	}))
	defer server.Close()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.WriteMessage(websocket.TextMessage, []byte("40"))
	select {
	case packet := <-got:
		if packet != "40" || early.Load() {
			t.Fatal("fallback did not wait for OPEN")
		}
	case <-time.After(time.Second):
		t.Fatal("fallback auth missing")
	}
	engineIOExpectClosed(t, closed)
}

func TestEngineIOCandidateRejectsMalformedOpenAndSID(t *testing.T) {
	for _, packet := range []string{
		``, `40{"sid":"secret"}`, `22:0{"sid":"secret"}`, `0{"sid":""}`,
		`0{"sid":"secret&transport=polling"}`, `0{"sid":"../private"}`,
		`0{"sid":"secret\nvalue"}`, `0{"sid":5}`, `0{"sid":"a"}trailer`,
		"0{\"sid\":\"a\"}\x1e40", `0{"sid":"` + strings.Repeat("s", 129) + `"}`,
		"0" + strings.Repeat(" ", engineIOMaxPacket) + `{}`,
	} {
		if _, _, err := engineIOOpen([]byte(packet)); err != errEngineIOOpen {
			t.Fatal("invalid OPEN did not return its fixed category")
		}
	}
	sid, upgrade, err := engineIOOpen([]byte(`0{"sid":"safe-1_a","upgrades":["websocket"],"extra":"allowed"}`))
	if err != nil || sid != "safe-1_a" || !upgrade {
		t.Fatal("valid v4 OPEN was rejected")
	}
}

func TestEngineIOCandidateQueryChangesAreStructuredAndOriginBound(t *testing.T) {
	valid := YandexDocsInfo{WsURL: "wss://editor.yandex.ru/doc/c/?EIO=4&transport=websocket&extra=a%26b", Origin: "https://editor.yandex.ru", Host: "editor.yandex.ru"}
	ws, poll, err := engineIOURLs(valid)
	if err != nil || ws.Query().Get("transport") != "websocket" || poll.Query().Get("transport") != "polling" ||
		poll.Query().Get("extra") != "a&b" || poll.Scheme != "https" {
		t.Fatal("query conversion damaged the request")
	}
	for _, raw := range []string{
		"ws://editor.yandex.ru/doc/c/?EIO=4&transport=websocket",
		"wss://other.yandex.ru/doc/c/?EIO=4&transport=websocket",
		"wss://private@editor.yandex.ru/doc/c/?EIO=4&transport=websocket",
		valid.WsURL + "&sid=old", valid.WsURL + "&EIO=3", valid.WsURL + "&transport=polling", valid.WsURL + "#private",
	} {
		info := valid
		info.WsURL = raw
		if _, _, err := engineIOURLs(info); err != errEngineIORequest {
			t.Fatal("invalid request was not rejected with a fixed category")
		}
	}
}

func TestEngineIOCandidatePollingRedirectDoesNotForwardBrowserCookies(t *testing.T) {
	var leaked atomic.Int32
	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		leaked.Add(1)
	}))
	defer other.Close()
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			http.SetCookie(w, &http.Cookie{Name: "redirect_state", Value: "must-not-use"})
			http.Redirect(w, r, other.URL+"/private", http.StatusFound)
			return
		}
		if _, err := r.Cookie("redirect_state"); err == nil {
			t.Error("failed polling state leaked into fallback")
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.WriteMessage(websocket.TextMessage, []byte(`0{"sid":"direct"}`))
		_, _, _ = conn.ReadMessage()
	}))
	defer server.Close()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	if leaked.Load() != 0 {
		t.Fatal("polling followed a cross-origin redirect")
	}
}

func TestEngineIOCandidatePollingBodyIsBounded(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, strings.Repeat("x", engineIOMaxPacket+1))
	}))
	defer server.Close()
	info := engineIOTestInfo(server)
	_, poll, _ := engineIOURLs(info)
	if _, _, err := engineIOPoll(context.Background(), info, poll, server.Client()); err != errEngineIOPoll {
		t.Fatal("oversized polling response was accepted")
	}
}

func TestEngineIOCandidateStopCancelsPollingAndClosesResponse(t *testing.T) {
	entered := make(chan struct{})
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done()
		close(closed)
	}))
	defer server.Close()
	tr := NewYandexDocsTransport("https://docs.yandex.ru/docs/synthetic", transport.DefaultConfig())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatal(err)
	}
	defer tr.Stop()
	result := make(chan error, 1)
	go func() {
		conn, err := engineIODial(tr.bootstrap.ctx, engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
		if conn != nil {
			_ = conn.Close()
		}
		result <- err
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("polling did not begin")
	}
	_ = tr.Stop()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Stop did not cancel the polling handshake")
		}
	case <-time.After(time.Second):
		t.Fatal("Stop left a handshake running")
	}
	engineIOExpectClosed(t, closed)
}

func TestEngineIOCandidateStopCancelsOpenWaitAndClosesWebSocket(t *testing.T) {
	entered := make(chan struct{})
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			http.NotFound(w, r)
			return
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		close(entered)
		_, _, _ = conn.ReadMessage()
		close(closed)
	}))
	defer server.Close()
	tr := NewYandexDocsTransport("https://docs.yandex.ru/docs/synthetic", transport.DefaultConfig())
	_ = tr.BaseTransport.Start()
	defer tr.Stop()
	result := make(chan error, 1)
	go func() {
		conn, err := engineIODial(tr.bootstrap.ctx, engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
		if conn != nil {
			_ = conn.Close()
		}
		result <- err
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("WebSocket did not begin")
	}
	_ = tr.Stop()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("Stop did not cancel the WebSocket handshake")
		}
	case <-time.After(time.Second):
		t.Fatal("Stop left a WebSocket handshake running")
	}
	engineIOExpectClosed(t, closed)
}

func TestEngineIOCandidateOpenTimeoutIsBoundedAndClosesSocket(t *testing.T) {
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			http.NotFound(w, r)
			return
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		_, _, _ = conn.ReadMessage()
		close(closed)
	}))
	defer server.Close()
	started := time.Now()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), 150*time.Millisecond)
	if conn != nil || err == nil || time.Since(started) > time.Second {
		t.Fatal("silent OPEN wait did not stop at its budget")
	}
	if err != context.DeadlineExceeded && err != errEngineIOOpen {
		t.Fatal("timeout exposed a transport error instead of a fixed category")
	}
	engineIOExpectClosed(t, closed)
}

func TestEngineIOCandidateBadProbeCannotReachSocketIO(t *testing.T) {
	for _, test := range []struct {
		name   string
		kind   int
		packet string
	}{
		{"unexpected", websocket.TextMessage, "42[private-provider-payload]"},
		{"binary", websocket.BinaryMessage, "3probe"},
		{"oversized", websocket.TextMessage, strings.Repeat("x", engineIOMaxPacket+1)},
	} {
		t.Run(test.name, func(t *testing.T) {
			closed := make(chan struct{})
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Query().Get("transport") == "polling" {
					fmt.Fprint(w, `0{"sid":"strict","upgrades":["websocket"]}`)
					return
				}
				conn, err := engineIOTestUpgrade(w, r)
				if err != nil {
					return
				}
				defer conn.Close()
				_, _, _ = conn.ReadMessage()
				_ = conn.WriteMessage(test.kind, []byte(test.packet))
				_, _, _ = conn.ReadMessage()
				close(closed)
			}))
			defer server.Close()
			conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
			if conn != nil || err != errEngineIOProbe {
				t.Fatal("invalid probe packet was not rejected with a fixed error")
			}
			engineIOExpectClosed(t, closed)
		})
	}
}

func TestEngineIOCandidateReadLimitIsRestoredAfterHandshake(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			http.NotFound(w, r)
			return
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.WriteMessage(websocket.TextMessage, []byte(`0{"sid":"direct"}`))
		_ = conn.WriteMessage(websocket.TextMessage, []byte(strings.Repeat("x", engineIOMaxPacket+1)))
		_, _, _ = conn.ReadMessage()
	}))
	defer server.Close()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	_, packet, err := conn.ReadMessage()
	if err != nil || len(packet) != engineIOMaxPacket+1 {
		t.Fatal("handshake read limit leaked into the application transport")
	}
}

func TestEngineIOCandidateCancelledContextMakesNoRequests(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1) }))
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	conn, err := engineIODial(ctx, engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if conn != nil || !errors.Is(err, context.Canceled) || requests.Load() != 0 {
		t.Fatal("cancelled handshake performed network work")
	}
}

func TestEngineIOCandidateProbeCancellationClosesSocketWithoutUpgrade(t *testing.T) {
	probing := make(chan struct{})
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			fmt.Fprint(w, `0{"sid":"strict","upgrades":["websocket"]}`)
			return
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		_, first, _ := conn.ReadMessage()
		if string(first) != "2probe" {
			t.Error("unexpected frame before probe")
		}
		close(probing)
		if _, _, err := conn.ReadMessage(); err == nil {
			t.Error("cancelled client sent upgrade or Socket.IO data")
		}
		close(closed)
	}))
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		conn, err := engineIODial(ctx, engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
		if conn != nil {
			_ = conn.Close()
		}
		result <- err
	}()
	select {
	case <-probing:
	case <-time.After(time.Second):
		t.Fatal("probe did not begin")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("probe did not preserve cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("probe remained active after cancellation")
	}
	engineIOExpectClosed(t, closed)
}

func TestEngineIOCandidateInvalidSIDFallsBackWithoutUsingIt(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			fmt.Fprint(w, `0{"sid":"unsafe&private=secret","upgrades":["websocket"]}`)
			return
		}
		if r.URL.Query().Has("sid") || r.URL.Query().Has("private") {
			t.Error("invalid SID reached the WebSocket URL")
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.WriteMessage(websocket.TextMessage, []byte(`0{"sid":"direct"}`))
		_, _, _ = conn.ReadMessage()
	}))
	defer server.Close()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
}

func TestEngineIOCandidateInvalidProofMakesNoRequests(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1) }))
	defer server.Close()
	for _, mutate := range []func(*YandexDocsInfo){
		func(info *YandexDocsInfo) { info.BrowserUserAgent = "private\r\nX-Leak: cookie" },
		func(info *YandexDocsInfo) { info.CookieStr = "private=cookie\r\nX-Leak: token" },
		func(info *YandexDocsInfo) { info.CookieStr = strings.Repeat("x", 16385) },
	} {
		info := engineIOTestInfo(server)
		mutate(&info)
		conn, err := engineIODial(context.Background(), info, server.Client(), engineIOTestDialer(server), time.Second)
		if conn != nil || err != errEngineIORequest || requests.Load() != 0 {
			t.Fatal("invalid browser proof was sent or exposed in an error")
		}
	}
}

func TestEngineIOCandidateDoesNotBypassWebSocketTLSVerification(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			http.NotFound(w, r)
			return
		}
		t.Error("untrusted WebSocket TLS reached the request handler")
	}))
	defer server.Close()
	// Polling trusts only this test server. The WebSocket dialer deliberately
	// lacks its synthetic root; the candidate must not copy or skip TLS trust.
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), &websocket.Dialer{}, time.Second)
	if conn != nil || err != errEngineIODial {
		t.Fatal("untrusted WebSocket TLS was not rejected with a fixed error")
	}
}

func TestEngineIOCandidateProbePacketCountIsBounded(t *testing.T) {
	closed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("transport") == "polling" {
			fmt.Fprint(w, `0{"sid":"strict","upgrades":["websocket"]}`)
			return
		}
		conn, err := engineIOTestUpgrade(w, r)
		if err != nil {
			return
		}
		defer conn.Close()
		_, _, _ = conn.ReadMessage()
		for range engineIOMaxProbePackets {
			_ = conn.WriteMessage(websocket.TextMessage, []byte("6"))
		}
		_, _, _ = conn.ReadMessage()
		close(closed)
	}))
	defer server.Close()
	started := time.Now()
	conn, err := engineIODial(context.Background(), engineIOTestInfo(server), server.Client(), engineIOTestDialer(server), time.Second)
	if conn != nil || err != errEngineIOProbe || time.Since(started) >= time.Second {
		t.Fatal("probe packet count was not bounded before the timeout")
	}
	engineIOExpectClosed(t, closed)
}

func TestYandexConnectToDocDispatchesPollingAndStopCancelsIt(t *testing.T) {
	tr := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	defer tr.Stop()
	if err := tr.ConfigureBrowserBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
		return &BrowserSession{Cookies: anonymousCookies(), UserAgent: engineIOTestAgent}, nil
	}); err != nil {
		t.Fatal(err)
	}
	var documentRequests atomic.Int32
	var pollingRequests atomic.Int32
	pollEntered := make(chan bool, 1)
	pollCancelled := make(chan struct{})
	// Keep the production fetch, browser retry and connectToDoc path. Its
	// existing HTTP injection point absorbs every request; Stop is issued
	// while polling is blocked, before any WebSocket dial is attempted.
	tr.bootstrap.client.Transport = bootstrapRoundTripper(func(request *http.Request) (*http.Response, error) {
		if request.URL.Host == "docs.yandex.ru" {
			if documentRequests.Add(1) == 1 {
				return bootstrapResponse(request, verificationHTML), nil
			}
			return bootstrapResponse(request, editorHTML), nil
		}
		if request.URL.Host != "office.yandex.ru" || pollingRequests.Add(1) != 1 {
			return nil, errors.New("unexpected synthetic handshake request")
		}
		cookie, cookieErr := request.Cookie("temporary")
		pollEntered <- request.Method == http.MethodGet && request.URL.Scheme == "https" &&
			request.URL.Query().Get("transport") == "polling" && request.URL.Query().Get("EIO") == "4" &&
			!request.URL.Query().Has("sid") && request.UserAgent() == engineIOTestAgent &&
			request.Header.Get("Origin") == "https://office.yandex.ru" && cookieErr == nil && cookie.Value == "opaque"
		<-request.Context().Done()
		close(pollCancelled)
		return nil, request.Context().Err()
	})
	if err := tr.Start(); err != nil {
		t.Fatal(err)
	}
	select {
	case valid := <-pollEntered:
		if !valid {
			t.Error("connectToDoc did not retain browser identity in Engine.IO polling")
		}
	case <-time.After(time.Second):
		t.Fatal("connectToDoc did not dispatch Engine.IO polling")
	}
	if err := tr.Stop(); err != nil {
		t.Fatal(err)
	}
	engineIOExpectClosed(t, pollCancelled)
	if documentRequests.Load() != 2 || pollingRequests.Load() != 1 || tr.IsRunning() || tr.IsConnected() {
		t.Fatal("stopped handshake changed connection state or retried")
	}
	tr.Mu.RLock()
	session := tr.session
	tr.Mu.RUnlock()
	if session != nil {
		t.Fatal("cancelled polling published a document session")
	}
}
