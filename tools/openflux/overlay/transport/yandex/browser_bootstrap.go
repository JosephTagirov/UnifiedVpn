// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"time"
)

var (
	ErrBrowserVerification = errors.New("Yandex browser verification is required")
	ErrBrowserSession      = errors.New("Yandex browser verification did not complete")
	ErrDocumentAccess      = errors.New("Yandex document editor is unavailable or does not allow editing")

	// Fixed stage names never include provider errors, response bodies or cookies.
	errBrowserProvider = fmt.Errorf("%w (stage=provider)", ErrBrowserSession)
	errBrowserTimeout  = fmt.Errorf("%w (stage=provider_timeout)", ErrBrowserSession)
	errBrowserCookies  = fmt.Errorf("%w (stage=cookies)", ErrBrowserSession)
	errBrowserDocument = fmt.Errorf("%w (stage=classic_document)", ErrBrowserSession)
	errBrowserHTTPS    = fmt.Errorf("%w (stage=retry_https)", ErrBrowserSession)
	errBrowserRepeated = fmt.Errorf("%w (stage=verification_repeated)", ErrBrowserSession)
	errBrowserBackoff  = fmt.Errorf("%w (stage=backoff)", ErrBrowserSession)
	errBrowserBudget   = fmt.Errorf("%w (stage=attempt_limit)", ErrBrowserSession)
)

// BrowserCookie is runtime-only anonymous browser state, never a profile option.
type BrowserCookie struct {
	Name    string `json:"name"`
	Value   string `json:"value"`
	Domain  string `json:"domain"`
	Path    string `json:"path"`
	Secure  bool   `json:"secure"`
	Expires int64  `json:"expires"`
}

// BrowserSession couples the proof with the browser identity that obtained it.
// An empty UserAgent is accepted only for the legacy cookie-only handoff.
type BrowserSession struct {
	Cookies         []BrowserCookie
	UserAgent       string
	ClassicDocument *ClassicDocument
}

type BrowserCookieProvider func(context.Context) (*BrowserSession, error)

type bootstrapResult struct {
	html            string
	classicDocument *ClassicDocument
}

const legacyBrowserUserAgent = "Mozilla/5.0"

func ValidBrowserUserAgent(value string) bool {
	if len(value) == 0 || len(value) > 1024 || strings.TrimSpace(value) != value {
		return false
	}
	for _, ch := range value {
		if ch < 0x20 || ch > 0x7e {
			return false
		}
	}
	return true
}

func browserUserAgent(value string) string {
	if value == "" {
		return legacyBrowserUserAgent
	}
	return value
}

type documentBootstrap struct {
	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	provider   BrowserCookieProvider
	client     *http.Client
	errors     chan error
	attempts   []time.Time
	retryAfter time.Time
	now        func() time.Time
	userAgent  string
}

func newDocumentBootstrap(ctx context.Context, provider BrowserCookieProvider) *documentBootstrap {
	if ctx == nil {
		ctx = context.Background()
	}
	ctx, cancel := context.WithCancel(ctx)
	jar, _ := cookiejar.New(nil)
	transport := http.DefaultTransport.(*http.Transport).Clone()
	// A pre-existing system VPN/proxy must not bootstrap a different exit IP.
	transport.Proxy = nil
	return &documentBootstrap{
		ctx: ctx, cancel: cancel, provider: provider, errors: make(chan error, 1), now: time.Now,
		client: &http.Client{Jar: jar, Transport: transport, Timeout: 15 * time.Second,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 5 || !allowedDocumentOrigin(req.URL) {
					return ErrDocumentAccess
				}
				return nil
			}},
	}
}

// ConfigureBrowserBootstrap must be called before Start. The provider and its
// cookies live only inside this transport's lifetime.
func (t *YandexDocsTransport) ConfigureBrowserBootstrap(ctx context.Context, provider BrowserCookieProvider) error {
	if ctx == nil || t.IsRunning() {
		return errors.New("browser bootstrap must be configured before transport startup")
	}
	if t.bootstrap != nil {
		t.bootstrap.close()
	}
	t.bootstrap = newDocumentBootstrap(ctx, provider)
	return nil
}

func (t *YandexDocsTransport) BrowserErrors() <-chan error {
	if t.bootstrap == nil {
		return nil
	}
	return t.bootstrap.errors
}

// CheckDocumentAccess uses the production bootstrap without joining the
// document's WebSocket room or exchanging tunnel packets.
func (t *YandexDocsTransport) CheckDocumentAccess() error {
	if t.IsRunning() {
		return errors.New("document diagnostic requires a stopped transport")
	}
	_, err := t.fetchDocInfo(t.url, "bootstrap-check")
	return err
}

func (b *documentBootstrap) close() {
	b.cancel()
	b.client.CloseIdleConnections()
}

func allowedDocumentOrigin(u *url.URL) bool {
	return u != nil && allowedDocumentURL(u.String())
}

func allowedBalancer(raw string) bool {
	u, ok := checkedBalancerOrigin(raw)
	return ok && yandexSubdomain(u.Hostname())
}

func isVerificationPage(body string) bool {
	if _, found, _ := clientConfigScript(body); found {
		return false
	}
	lower := strings.ToLower(body)
	return strings.Contains(lower, "javascript") &&
		((strings.Contains(lower, "\u0432\u0435\u0440\u0438\u0444\u0438\u043a\u0430\u0446\u0438\u044f") &&
			strings.Contains(lower, "\u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0431\u0440\u0430\u0443\u0437\u0435\u0440\u0430")) ||
			strings.Contains(lower, "verify your browser") || strings.Contains(lower, "browser verification") ||
			strings.Contains(lower, "showcaptcha"))
}

func ValidateBrowserCookies(document string, values []BrowserCookie, now time.Time) ([]*http.Cookie, error) {
	u, err := url.Parse(document)
	if err != nil || !allowedDocumentURL(document) || len(values) == 0 || len(values) > 64 {
		return nil, ErrBrowserSession
	}
	cookies := make([]*http.Cookie, 0, len(values))
	documentHost := strings.ToLower(u.Hostname())
	seen := make(map[string]bool)
	total := 0
	for _, value := range values {
		domain := strings.ToLower(value.Domain)
		name := strings.ToLower(value.Name)
		exactDocumentHost := domain == "docs.yandex.ru" || domain == "disk.yandex.ru"
		if name == "session_id" || name == "sessionid2" || name == "yandex_login" ||
			(domain != "" && !exactDocumentHost && domain != "."+documentHost && domain != "yandex.ru" && domain != ".yandex.ru") ||
			value.Path != "/" || !value.Secure || len(value.Name) > 128 || len(value.Value) > 4096 ||
			value.Expires < 0 || (value.Expires != 0 && value.Expires <= now.Unix()) {
			return nil, ErrBrowserSession
		}
		identityDomain := strings.TrimPrefix(domain, ".")
		if identityDomain == "" {
			identityDomain = documentHost
		}
		// Android reads each permitted origin separately. Preserve host-only
		// scope across Disk -> Docs redirects, without granting subdomain scope.
		if exactDocumentHost {
			domain = ""
		}
		cookie := &http.Cookie{Name: value.Name, Value: value.Value, Domain: domain, Path: "/", Secure: true, HttpOnly: true}
		if cookie.Valid() != nil {
			return nil, ErrBrowserSession
		}
		identity := name + "\x00" + identityDomain
		if seen[identity] {
			return nil, ErrBrowserSession
		}
		seen[identity] = true
		total += len(value.Name) + len(value.Value) + len(domain) + len(value.Path)
		if total > 24576 {
			return nil, ErrBrowserSession
		}
		// Browser identifiers may otherwise have multi-year expiry dates.
		cookie.Expires = now.Add(2 * time.Hour)
		if value.Expires > 0 && value.Expires < cookie.Expires.Unix() {
			cookie.Expires = time.Unix(value.Expires, 0)
		}
		cookies = append(cookies, cookie)
	}
	return cookies, nil
}

func browserCookieJar(document string, values []BrowserCookie, now time.Time) (http.CookieJar, error) {
	cookies, err := ValidateBrowserCookies(document, values, now)
	if err != nil {
		return nil, err
	}
	jar, _ := cookiejar.New(nil)
	original, _ := url.Parse(document)
	for i, cookie := range cookies {
		origin := original
		domain := strings.ToLower(values[i].Domain)
		if domain == "docs.yandex.ru" || domain == "disk.yandex.ru" {
			origin = &url.URL{Scheme: "https", Host: domain, Path: "/"}
		}
		jar.SetCookies(origin, []*http.Cookie{cookie})
	}
	return jar, nil
}

func (b *documentBootstrap) publish(err error) {
	if b.ctx.Err() != nil {
		return
	}
	select {
	case b.errors <- err:
	default:
	}
}

func (b *documentBootstrap) request(document string) (string, error) {
	if !allowedDocumentURL(document) {
		return "", ErrDocumentAccess
	}
	req, err := http.NewRequestWithContext(b.ctx, http.MethodGet, document, nil)
	if err != nil {
		return "", ErrDocumentAccess
	}
	req.Header.Set("User-Agent", browserUserAgent(b.userAgent))
	response, err := b.client.Do(req)
	if err != nil {
		if b.ctx.Err() != nil {
			return "", b.ctx.Err()
		}
		// Never return url.Error: it includes the private document URL.
		return "", errors.New("Yandex document HTTPS request failed")
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024+1))
	if err != nil || len(body) > 4*1024*1024 {
		return "", ErrDocumentAccess
	}
	if response.StatusCode != http.StatusOK &&
		!((response.StatusCode == http.StatusForbidden || response.StatusCode == http.StatusTooManyRequests) && isVerificationPage(string(body))) {
		return "", ErrDocumentAccess
	}
	return string(body), nil
}

func (b *documentBootstrap) fetch(document string) (bootstrapResult, error) {
	return b.fetchDocument(document, true)
}

func (b *documentBootstrap) fetchVolga(document string) (bootstrapResult, error) {
	return b.fetchDocument(document, false)
}

func (b *documentBootstrap) fetchDocument(document string, allowClassic bool) (bootstrapResult, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.ctx.Err() != nil {
		return bootstrapResult{}, b.ctx.Err()
	}
	if b.now().Before(b.retryAfter) {
		return bootstrapResult{}, errBrowserBackoff
	}
	body, err := b.request(document)
	if err != nil || !isVerificationPage(body) {
		return bootstrapResult{html: body}, err
	}
	if b.provider == nil {
		b.retryAfter = b.now().Add(time.Minute)
		b.publish(ErrBrowserVerification)
		return bootstrapResult{}, ErrBrowserVerification
	}
	cutoff := b.now().Add(-5 * time.Minute)
	retained := b.attempts[:0]
	for _, attempt := range b.attempts {
		if attempt.After(cutoff) {
			retained = append(retained, attempt)
		}
	}
	b.attempts = retained
	if len(b.attempts) >= 2 {
		b.retryAfter = b.attempts[0].Add(5 * time.Minute)
		b.publish(errBrowserBudget)
		return bootstrapResult{}, errBrowserBudget
	}
	b.attempts = append(b.attempts, b.now())
	ctx, cancel := context.WithTimeout(b.ctx, 50*time.Second)
	session, err := b.provider(ctx)
	if err == nil {
		err = ctx.Err()
	}
	cancel()
	failure := errBrowserProvider
	if errors.Is(err, context.DeadlineExceeded) {
		failure = errBrowserTimeout
	}
	if err == nil && b.ctx.Err() == nil {
		var jar http.CookieJar
		failure = errBrowserCookies
		if session == nil || session.UserAgent != "" && !ValidBrowserUserAgent(session.UserAgent) {
			err = ErrBrowserSession
		} else {
			jar, err = browserCookieJar(document, session.Cookies, b.now())
		}
		if err == nil && session.ClassicDocument != nil {
			failure = errBrowserDocument
			if !allowClassic {
				err = ErrBrowserSession
			} else {
				err = ValidateClassicDocument(document, session.ClassicDocument, b.now())
			}
		}
		if err == nil {
			// Replace rather than merge state from a previous verification session.
			b.client.Jar = jar
			b.userAgent = session.UserAgent
			if session.ClassicDocument != nil {
				// Use only this nonce-bound result, not a cached document or synthetic HTML.
				metadata := *session.ClassicDocument
				return bootstrapResult{classicDocument: &metadata}, nil
			}
			failure = errBrowserHTTPS
			body, err = b.request(document)
			if err == nil {
				if !isVerificationPage(body) {
					return bootstrapResult{html: body}, nil
				}
				failure = errBrowserRepeated
			}
		}
	}
	if b.ctx.Err() != nil {
		return bootstrapResult{}, b.ctx.Err()
	}
	b.retryAfter = b.now().Add(time.Minute)
	b.publish(failure)
	return bootstrapResult{}, failure
}

func (b *documentBootstrap) requestUserAgent() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return browserUserAgent(b.userAgent)
}

func (b *documentBootstrap) cookiesFor(origin string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	u, err := url.Parse(origin)
	if err != nil || !allowedBalancer(origin) {
		return ""
	}
	var cookies []string
	for _, cookie := range b.client.Jar.Cookies(u) {
		cookies = append(cookies, cookie.Name+"="+cookie.Value)
	}
	return strings.Join(cookies, "; ")
}
