// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"openflux/transport"
)

func volgaPage(change func(map[string]any, map[string]any)) string {
	action := map[string]any{"office_online_editor_type": "volga", "action_url": "https://volga.yandex.ru/document/auth/initial",
		"access_token": "secret-token", "access_token_ttl": json.Number("1790000000000"), "editor_config": map[string]any{"documentType": "text"}}
	root := map[string]any{"officeActionData": action, "rights": []any{"write"}}
	if change != nil {
		change(root, action)
	}
	data, _ := json.Marshal(root)
	return `<script type="application/json" id="client-config">` + string(data) + `</script>`
}

func TestVolgaDocumentBootstrapShapeAndDeniedPermissions(t *testing.T) {
	valid, err := parseVolgaDocument(volgaPage(nil))
	if err != nil || valid.ttl != "1790000000000" {
		t.Fatal("valid Volga document rejected", err)
	}
	for name, change := range map[string]func(map[string]any, map[string]any){
		"classic":        func(_, a map[string]any) { a["office_online_editor_type"] = "onlyoffice" },
		"read only":      func(r, _ map[string]any) { r["rights"] = []any{"read"} },
		"invalid rights": func(r, _ map[string]any) { r["rights"] = "write" },
		"nested denied":  func(r, _ map[string]any) { r["editorParams"] = map[string]any{"rights": []any{"read"}} },
		"edit denied": func(_, a map[string]any) {
			a["editor_config"] = map[string]any{"documentType": "text", "document": map[string]any{"permissions": map[string]any{"edit": false}}}
		},
		"wrong type": func(_, a map[string]any) { a["editor_config"] = map[string]any{"documentType": "spreadsheet"} },
		"no token":   func(_, a map[string]any) { delete(a, "access_token") },
		"plaintext":  func(_, a map[string]any) { a["action_url"] = "http://volga.yandex.ru/document/auth/initial" },
		"foreign":    func(_, a map[string]any) { a["action_url"] = "https://volga.yandex.ru.example.org/secret" },
		"userinfo":   func(_, a map[string]any) { a["action_url"] = "https://secret@volga.yandex.ru/" },
	} {
		t.Run(name, func(t *testing.T) {
			_, err := parseVolgaDocument(volgaPage(change))
			if err == nil || strings.Contains(err.Error(), "secret") {
				t.Fatal("unsafe document accepted or details leaked")
			}
		})
	}
	if _, err := parseVolgaDocument(volgaPage(nil) + volgaPage(nil)); err == nil {
		t.Fatal("duplicate config accepted")
	}
}

func TestVolgaAuthorizationUsesBootstrapJarAndBoundedOrigins(t *testing.T) {
	for _, foreign := range []bool{false, true} {
		t.Run(map[bool]string{false: "accepted", true: "foreign redirect blocked"}[foreign], func(t *testing.T) {
			tp := NewYandexVolgaTransport(testDocumentURL, transport.DefaultConfig())
			defer tp.Stop()
			requests := 0
			tp.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
				requests++
				switch requests {
				case 1:
					return bootstrapResponse(req, volgaPage(nil)), nil
				case 2:
					if req.URL.Host != "volga.yandex.ru" || req.Method != "POST" {
						t.Fatal("authorization left allowed origin")
					}
					if req.ParseForm() != nil || req.Form.Get("access_token") != "secret-token" {
						t.Fatal("missing token")
					}
					metadata, _ := json.Marshal(map[string]any{"sessionId": "session", "userId": 12,
						"xiva": map[string]any{"user": "user", "sign": "signature", "ts": "123"}})
					query := url.Values{"json": {string(metadata)}, "token": {"secret-bearer"}, "request-path": {"test-path"}}
					host := "volga.yandex.ru"
					if foreign {
						host = "example.org"
					}
					return &http.Response{StatusCode: 302, Request: req, Header: http.Header{"Location": {"https://" + host + "/document/?" + query.Encode()}}, Body: io.NopCloser(strings.NewReader(""))}, nil
				default:
					if req.URL.Host != "volga.yandex.ru" {
						t.Fatal("redirect left allowed origin")
					}
					return bootstrapResponse(req, ""), nil
				}
			})
			auth, err := tp.authorizeDocument()
			if foreign {
				if err == nil || requests != 2 || strings.Contains(err.Error(), "secret") {
					t.Fatal("redirect safety failed")
				}
			} else if err != nil || requests != 3 || auth.UserID != 12 || auth.RequestPath != "test-path" {
				t.Fatal("authorization failed", err)
			}
		})
	}
}

func TestVolgaStopCancelsBootstrapAndPreventsRestart(t *testing.T) {
	tp := NewYandexVolgaTransport(testDocumentURL, transport.DefaultConfig())
	entered := make(chan struct{})
	tp.ConfigureBrowserBootstrap(context.Background(), func(ctx context.Context) ([]BrowserCookie, error) {
		close(entered)
		<-ctx.Done()
		return nil, ctx.Err()
	})
	tp.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		return bootstrapResponse(req, verificationHTML), nil
	})
	started, stopped := make(chan error, 1), make(chan error, 1)
	go func() { started <- tp.Start() }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("bootstrap not entered")
	}
	go func() { stopped <- tp.Stop() }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("stop blocked")
	}
	if err := <-started; err == nil {
		t.Fatal("cancelled start accepted")
	}
	if tp.Start() == nil || tp.Send([]byte("no")) == nil {
		t.Fatal("stopped transport reused")
	}
	if err := tp.Stop(); err != nil {
		t.Fatal(err)
	}
}

func TestVolgaRelaySendStopIsSafeAndPayloadFitsFraming(t *testing.T) {
	cfg := DefaultVolgaConfig()
	if cfg.WorkerCount > 16 || cfg.QueueSize > 256 || cfg.MaxPayloadBytes > 65535 {
		t.Fatal("unbounded defaults")
	}
	r := newRelayClient(&volgaAuth{Session: &http.Client{}}, cfg, &VolgaStats{})
	if r.Send(make([]byte, 65536)) == nil {
		t.Fatal("uint16 overflow accepted")
	}
	var workers sync.WaitGroup
	for i := 0; i < 8; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for j := 0; j < 100; j++ {
				_ = r.Send([]byte("fixture"))
			}
		}()
	}
	r.Stop()
	workers.Wait()
	r.Stop()
	if r.Send([]byte("after")) == nil {
		t.Fatal("send after stop accepted")
	}
}

func TestVolgaRelayAuthorizationFailureIsPropagated(t *testing.T) {
	r := newRelayClient(&volgaAuth{Session: &http.Client{}, RequestPath: "test"}, DefaultVolgaConfig(), &VolgaStats{})
	defer r.Stop()
	r.httpClient.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 401, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("private-server-message")), Request: req}, nil
	})
	notified := false
	r.onAuthFailure = func() { notified = true }
	err := r.sendBatch([][]byte{[]byte("fixture")})
	if err == nil || !notified || r.ctx.Err() == nil || strings.Contains(err.Error(), "private") {
		t.Fatal("authorization failure was hidden")
	}
}

func TestVolgaWebSocketAuthorizationFailureStopsStaleRetries(t *testing.T) {
	for _, status := range []int{401, 403, 502} {
		r := newRelayClient(&volgaAuth{Session: &http.Client{}}, DefaultVolgaConfig(), &VolgaStats{})
		w := newWSListener(r.auth, r.config, r.stats, r, nil)
		notified := false
		r.onAuthFailure = func() { notified = true }
		w.rejectAuthorization(status)
		denied := status == 401 || status == 403
		if notified != denied || (w.ctx.Err() != nil) != denied {
			t.Fatal("wrong WebSocket retry decision", status)
		}
		w.Stop()
		r.Stop()
	}
}

func TestVolgaSessionFailureSurvivesEarlierBrowserError(t *testing.T) {
	tp := NewYandexVolgaTransport(testDocumentURL, transport.DefaultConfig())
	defer tp.Stop()
	tp.bootstrap.publish(ErrBrowserVerification)
	tp.failSession()
	select {
	case err := <-tp.BrowserErrors():
		if err != ErrVolgaSession {
			t.Fatal("fatal error lost")
		}
	default:
		t.Fatal("fatal error missing")
	}
}

func TestVolgaWebSocketCookieScope(t *testing.T) {
	jar, _ := cookiejar.New(nil)
	volga, _ := url.Parse("https://volga.yandex.ru/document/")
	jar.SetCookies(volga, []*http.Cookie{{Name: "volga_only", Value: "private", Path: "/", Secure: true},
		{Name: "shared_yandex", Value: "anonymous", Domain: ".yandex.ru", Path: "/", Secure: true}})
	w := &wsListener{auth: &volgaAuth{Session: &http.Client{Jar: jar}}}
	if h := w.cookieHeader(); strings.Contains(h, "volga_only") || !strings.Contains(h, "shared_yandex=anonymous") {
		t.Fatal("cookie scope changed")
	}
}

func TestVolgaServerRetryCancelsWithoutResettingBrowserBudget(t *testing.T) {
	tp := NewYandexVolgaTransport(testDocumentURL, transport.DefaultConfig())
	tp.EnableServerRetries()
	entered := make(chan struct{})
	tp.ConfigureBrowserBootstrap(context.Background(), func(context.Context) ([]BrowserCookie, error) { close(entered); return nil, ErrBrowserSession })
	tp.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) { return bootstrapResponse(req, verificationHTML), nil })
	started, stopped := make(chan error, 1), make(chan error, 1)
	go func() { started <- tp.Start() }()
	<-entered
	go func() { stopped <- tp.Stop() }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("server retry cancellation blocked")
	}
	if <-started == nil {
		t.Fatal("cancelled server started")
	}
	if len(tp.bootstrap.attempts) != 1 {
		t.Fatal("browser budget reset")
	}
}
