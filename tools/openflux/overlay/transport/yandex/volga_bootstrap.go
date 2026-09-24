// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/html"
)

var errVolgaDocument = errors.New("Yandex Volga text document is unavailable or does not allow editing")
var ErrVolgaSession = errors.New("Yandex Volga session expired or permission denied")
var volgaRequestPath = regexp.MustCompile(`^[a-zA-Z0-9_.:-]{1,512}$`)

type volgaDocument struct {
	action, token, ttl string
}

// Servers retain their browser retry budget instead of restarting after each challenge.
func (t *YandexVolgaTransport) EnableServerRetries() { t.retryAuthorization = true }

func (t *YandexVolgaTransport) authorizeWithRetry() (*volgaAuth, error) {
	for {
		auth, err := t.authorizeDocument()
		if err == nil || !t.retryAuthorization || t.bootstrap.ctx.Err() != nil {
			return auth, err
		}
		timer := time.NewTimer(time.Minute)
		select {
		case <-t.bootstrap.ctx.Done():
			timer.Stop()
			return nil, t.bootstrap.ctx.Err()
		case <-timer.C:
		}
	}
}

func (t *YandexVolgaTransport) ConfigureBrowserBootstrap(ctx context.Context, provider BrowserCookieProvider) error {
	t.lifecycle.Lock()
	defer t.lifecycle.Unlock()
	if ctx == nil || t.IsRunning() || t.stopped {
		return errors.New("browser bootstrap must be configured before transport startup")
	}
	if t.bootstrap != nil {
		t.bootstrap.close()
	}
	t.bootstrap = newDocumentBootstrap(ctx, provider)
	return nil
}

func (t *YandexVolgaTransport) BrowserErrors() <-chan error {
	return t.sessionErrors
}

func (t *YandexVolgaTransport) failSession() {
	t.SetConnected(false)
	// Fatal session state cannot be lost behind a recoverable browser-bootstrap error.
	select {
	case t.sessionErrors <- ErrVolgaSession:
	default:
	}
}

func (t *YandexVolgaTransport) CheckDocumentAccess() error {
	if t.IsRunning() {
		return errors.New("document diagnostic requires a stopped transport")
	}
	body, err := t.bootstrap.fetch(t.docURL)
	if err != nil {
		return err
	}
	_, err = parseVolgaDocument(body)
	return err
}

func allowedVolgaURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && len(raw) <= 32768 && u.Scheme == "https" && u.User == nil &&
		u.Hostname() == "volga.yandex.ru" && (u.Port() == "" || u.Port() == "443") &&
		u.Fragment == "" && !strings.ContainsAny(raw, "\r\n\t ")
}

func parseVolgaDocument(body string) (volgaDocument, error) {
	z := html.NewTokenizer(strings.NewReader(body))
	var script string
	found := false
	for {
		switch z.Next() {
		case html.ErrorToken:
			if z.Err() != io.EOF {
				return volgaDocument{}, errVolgaDocument
			}
			if !found {
				return volgaDocument{}, errVolgaDocument
			}
			goto decode
		case html.StartTagToken:
			token := z.Token()
			if token.Data != "script" {
				continue
			}
			match := false
			for _, attr := range token.Attr {
				if attr.Key == "id" && attr.Val == "client-config" {
					match = true
				}
			}
			if !match {
				continue
			}
			if found || z.Next() != html.TextToken {
				return volgaDocument{}, errVolgaDocument
			}
			found, script = true, string(z.Text())
		}
	}
decode:
	var root map[string]any
	d := json.NewDecoder(strings.NewReader(script))
	d.UseNumber()
	if d.Decode(&root) != nil || d.Decode(new(any)) != io.EOF {
		return volgaDocument{}, errVolgaDocument
	}
	action, _ := root["officeActionData"].(map[string]any)
	editor, _ := action["editor_config"].(map[string]any)
	if action["office_online_editor_type"] != "volga" || editor["documentType"] != "text" {
		return volgaDocument{}, errVolgaDocument
	}
	// An explicit permission denial must never be interpreted as editable.
	params, _ := root["editorParams"].(map[string]any)
	for _, object := range []map[string]any{root, action, params} {
		if rights, exists := object["rights"]; exists {
			items, ok := rights.([]any)
			write := false
			for _, right := range items {
				if right == "write" {
					write = true
				}
			}
			if !ok || !write {
				return volgaDocument{}, errVolgaDocument
			}
		}
	}
	document, _ := editor["document"].(map[string]any)
	permissions, _ := document["permissions"].(map[string]any)
	if edit, exists := permissions["edit"]; exists && edit != true {
		return volgaDocument{}, errVolgaDocument
	}
	endpoint, _ := action["action_url"].(string)
	token, _ := action["access_token"].(string)
	if !allowedVolgaURL(endpoint) || len(token) == 0 || len(token) > 16384 {
		return volgaDocument{}, errVolgaDocument
	}
	return volgaDocument{action: endpoint, token: token, ttl: formatTTL(action["access_token_ttl"])}, nil
}

func (t *YandexVolgaTransport) authorizeDocument() (*volgaAuth, error) {
	b := t.bootstrap
	body, err := b.fetch(t.docURL)
	if err != nil {
		return nil, err
	}
	doc, err := parseVolgaDocument(body)
	if err != nil {
		return nil, err
	}
	// The cookie jar stays in memory and is shared only with these exact Yandex origins.
	session := *b.client
	session.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	form := url.Values{"access_token": {doc.token}, "access_token_ttl": {doc.ttl}}
	req, err := http.NewRequestWithContext(b.ctx, http.MethodPost, doc.action, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, errVolgaDocument
	}
	req.Header.Set("User-Agent", volgaUserAgent)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://disk.yandex.ru")
	req.Header.Set("Referer", t.docURL)
	response, err := session.Do(req)
	if err != nil {
		return nil, errors.New("Yandex Volga authorization HTTPS failed")
	}
	response.Body.Close()
	if response.StatusCode != http.StatusFound {
		return nil, fmt.Errorf("Yandex Volga authorization rejected (HTTP %d)", response.StatusCode)
	}
	location := response.Header.Get("Location")
	base, _ := url.Parse(doc.action)
	parsed, err := url.Parse(location)
	if err != nil || location == "" {
		return nil, errors.New("Yandex Volga authorization redirect is invalid")
	}
	parsed = base.ResolveReference(parsed)
	if !allowedVolgaURL(parsed.String()) || strings.Contains(parsed.Path, "/document/error/") {
		return nil, errors.New("Yandex Volga authorization redirect was rejected")
	}
	query := parsed.Query()
	var data map[string]any
	d := json.NewDecoder(strings.NewReader(query.Get("json")))
	d.UseNumber()
	if d.Decode(&data) != nil || d.Decode(new(any)) != io.EOF {
		return nil, errors.New("Yandex Volga session metadata is invalid")
	}
	user, err := strconv.ParseInt(getStr(data, "userId"), 10, 32)
	xiva, _ := data["xiva"].(map[string]any)
	auth := &volgaAuth{Session: &session, Token: query.Get("token"), RequestPath: query.Get("request-path"),
		UserID: int(user), SessionID: getStr(data, "sessionId"), UserIDStr: getStr(xiva, "user"),
		Sign: getStr(xiva, "sign"), TS: getStr(xiva, "ts")}
	if err != nil || user <= 0 || !volgaRequestPath.MatchString(auth.RequestPath) || auth.RequestPath == "." || auth.RequestPath == ".." ||
		auth.Token == "" || len(auth.Token) > 16384 || auth.UserIDStr == "" || auth.Sign == "" || auth.SessionID == "" || auth.TS == "" {
		return nil, errors.New("Yandex Volga session metadata is incomplete")
	}
	req, err = http.NewRequestWithContext(b.ctx, http.MethodGet, parsed.String(), nil)
	if err != nil {
		return nil, errVolgaDocument
	}
	req.Header.Set("User-Agent", volgaUserAgent)
	req.Header.Set("Referer", doc.action)
	response, err = session.Do(req)
	if err != nil {
		return nil, errors.New("Yandex Volga session HTTPS failed")
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Yandex Volga session rejected (HTTP %d)", response.StatusCode)
	}
	auth.Cookies = session.Jar.Cookies(parsed)
	return auth, nil
}
