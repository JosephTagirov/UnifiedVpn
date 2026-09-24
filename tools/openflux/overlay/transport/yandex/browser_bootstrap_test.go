// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"testing"
	"time"

	"openflux/transport"
)

const testDocumentURL = "https://docs.yandex.ru/docs/view?url=private-document"
const verificationHTML = `<html><title>Browser verification</title>Enable JavaScript</html>`
const editorHTML = `<script id="client-config">{"officeActionData":{"balancer_url":"https://office.yandex.ru","editor_config":{"token":"private-token","document":{"key":"private-key","permissions":{"edit":true}}}}}</script>`

type bootstrapRoundTripper func(*http.Request) (*http.Response, error)

func (r bootstrapRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) { return r(req) }

func bootstrapResponse(req *http.Request, body string) *http.Response {
	return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body)), Request: req}
}

func anonymousCookies() []BrowserCookie {
	return []BrowserCookie{{Name: "temporary", Value: "opaque", Domain: ".yandex.ru", Path: "/", Secure: true}}
}

func TestBrowserCookiesValidation(t *testing.T) {
	now := time.Now()
	for _, domain := range []string{"", "docs.yandex.ru", ".docs.yandex.ru", "yandex.ru", ".yandex.ru"} {
		cookies := anonymousCookies()
		cookies[0].Domain = domain
		cookies[0].Expires = now.Add(365 * 24 * time.Hour).Unix()
		got, err := ValidateBrowserCookies(testDocumentURL, cookies, now)
		if err != nil || len(got) != 1 || !got[0].Secure || !got[0].HttpOnly || !got[0].Expires.Equal(now.Add(2*time.Hour)) {
			t.Fatal("valid ephemeral cookie not clamped")
		}
	}
	for name, mutate := range map[string]func(*BrowserCookie){
		"account":           func(c *BrowserCookie) { c.Name = "Session_id" },
		"secondary account": func(c *BrowserCookie) { c.Name = "sessionid2" },
		"login":             func(c *BrowserCookie) { c.Name = "YANDEX_LOGIN" },
		"empty name":        func(c *BrowserCookie) { c.Name = "" },
		"foreign host":      func(c *BrowserCookie) { c.Domain = "attacker.example" },
		"suffix trick":      func(c *BrowserCookie) { c.Domain = "yandex.ru.attacker.example" },
		"sibling host":      func(c *BrowserCookie) { c.Domain = "passport.yandex.ru" },
		"path":              func(c *BrowserCookie) { c.Path = "/docs" },
		"insecure":          func(c *BrowserCookie) { c.Secure = false },
		"expired":           func(c *BrowserCookie) { c.Expires = now.Add(-time.Second).Unix() },
		"negative expiry":   func(c *BrowserCookie) { c.Expires = -1 },
		"header injection":  func(c *BrowserCookie) { c.Value = "value\r\nHost: attacker" },
		"semicolon":         func(c *BrowserCookie) { c.Value = "x;y=z" },
		"long name":         func(c *BrowserCookie) { c.Name = strings.Repeat("n", 129) },
		"long value":        func(c *BrowserCookie) { c.Value = strings.Repeat("v", 4097) },
	} {
		t.Run(name, func(t *testing.T) {
			cookies := anonymousCookies()
			mutate(&cookies[0])
			if _, err := ValidateBrowserCookies(testDocumentURL, cookies, now); !errors.Is(err, ErrBrowserSession) {
				t.Fatal("unsafe browser cookie accepted")
			}
		})
	}
	duplicate := append(anonymousCookies(), anonymousCookies()...)
	if _, err := ValidateBrowserCookies(testDocumentURL, duplicate, now); err == nil {
		t.Fatal("duplicate cookie accepted")
	}
	if _, err := ValidateBrowserCookies(testDocumentURL, make([]BrowserCookie, 65), now); err == nil {
		t.Fatal("too many cookies accepted")
	}
	if _, err := ValidateBrowserCookies(testDocumentURL, nil, now); err == nil {
		t.Fatal("empty cookie set accepted")
	}
	androidCookies := anonymousCookies()
	androidCookies[0].Domain = "docs.yandex.ru"
	validated, err := ValidateBrowserCookies(testDocumentURL, androidCookies, now)
	if err != nil {
		t.Fatal("Android host-only cookie rejected")
	}
	jar, _ := cookiejar.New(nil)
	document, _ := url.Parse(testDocumentURL)
	child, _ := url.Parse("https://child.docs.yandex.ru")
	jar.SetCookies(document, validated)
	if len(jar.Cookies(document)) != 1 || len(jar.Cookies(child)) != 0 {
		t.Fatal("Android host-only cookie scope was broadened")
	}
}

func TestBootstrapOriginAndRedirectPolicy(t *testing.T) {
	b := newDocumentBootstrap(context.Background(), nil)
	defer b.close()
	if b.client.Transport.(*http.Transport).Proxy != nil {
		t.Fatal("bootstrap inherited a proxy and could use a different exit IP")
	}
	for _, target := range []string{
		"http://docs.yandex.ru/", "https://docs.yandex.ru:8443/", "https://docs.yandex.ru.attacker.example/",
		"https://passport.yandex.ru/", "https://user@docs.yandex.ru/", "https://127.0.0.1/", "https://docs.yandex.ru/#fragment",
	} {
		u, _ := url.Parse(target)
		if allowedDocumentOrigin(u) || b.client.CheckRedirect(&http.Request{URL: u}, nil) == nil {
			t.Fatal("unsafe document target allowed")
		}
	}
	u, _ := url.Parse(testDocumentURL)
	if b.client.CheckRedirect(&http.Request{URL: u}, make([]*http.Request, 5)) == nil {
		t.Fatal("redirect limit ignored")
	}
	upper, _ := url.Parse("HTTPS://DOCS.YANDEX.RU/docs/test")
	if !allowedDocumentOrigin(upper) {
		t.Fatal("valid existing uppercase-host profile rejected")
	}
	if _, err := ValidateBrowserCookies(upper.String(), []BrowserCookie{{Name: "temporary", Value: "opaque", Domain: "docs.yandex.ru", Path: "/", Secure: true}}, time.Now()); err != nil {
		t.Fatal("uppercase-host profile cookie comparison was not canonicalized")
	}
	for _, target := range []string{"http://office.yandex.ru", "https://yandex.ru.attacker.example", "https://office.yandex.ru/private", "https://office.yandex.ru?key=secret", "https://127.0.0.1", "https://user@office.yandex.ru"} {
		if allowedBalancer(target) {
			t.Fatal("untrusted editor WebSocket origin allowed")
		}
	}
}

func TestAndroidCookiesSurviveRedirectWithoutBroadeningHosts(t *testing.T) {
	values := []BrowserCookie{
		{Name: "proof", Value: "disk-proof", Domain: "disk.yandex.ru", Path: "/", Secure: true},
		{Name: "proof", Value: "docs-proof", Domain: "docs.yandex.ru", Path: "/", Secure: true},
	}
	b := newDocumentBootstrap(context.Background(), func(context.Context) ([]BrowserCookie, error) { return values, nil })
	defer b.close()
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		cookie, _ := req.Cookie("proof")
		if cookie == nil {
			return bootstrapResponse(req, verificationHTML), nil
		}
		if req.URL.Hostname() == "disk.yandex.ru" {
			if cookie.Value != "disk-proof" {
				t.Fatal("wrong origin's cookie")
			}
			response := bootstrapResponse(req, "")
			response.StatusCode = http.StatusFound
			response.Header.Set("Location", testDocumentURL)
			return response, nil
		}
		if cookie.Value != "docs-proof" {
			t.Fatal("redirect lost its host-only cookie")
		}
		return bootstrapResponse(req, editorHTML), nil
	})
	if body, err := b.fetch("https://disk.yandex.ru/i/synthetic"); err != nil || body != editorHTML {
		t.Fatal("two-origin anonymous handoff failed")
	}
	for _, host := range []string{"yandex.ru", "passport.yandex.ru", "child.docs.yandex.ru", "child.disk.yandex.ru", "office.yandex.ru", "attacker.invalid"} {
		u := &url.URL{Scheme: "https", Host: host, Path: "/"}
		if len(b.client.Jar.Cookies(u)) != 0 {
			t.Fatal("cookie escaped its exact document host")
		}
	}
}

func TestHostOnlyCookieAliasesCannotHideDuplicates(t *testing.T) {
	for _, domain := range []string{"", ".docs.yandex.ru", "DOCS.YANDEX.RU"} {
		values := []BrowserCookie{
			{Name: "proof", Value: "one", Domain: "docs.yandex.ru", Path: "/", Secure: true},
			{Name: "PROOF", Value: "two", Domain: domain, Path: "/", Secure: true},
		}
		if _, err := browserCookieJar(testDocumentURL, values, time.Now()); err == nil {
			t.Fatal("duplicate origin identity accepted")
		}
	}
	values := []BrowserCookie{{Name: "proof", Value: "one", Domain: ".disk.yandex.ru", Path: "/", Secure: true}}
	if _, err := browserCookieJar(testDocumentURL, values, time.Now()); err == nil {
		t.Fatal("redirect cookie obtained subdomain privileges")
	}
}

func TestBootstrapVerificationDetection(t *testing.T) {
	for _, body := range []string{verificationHTML, "JavaScript verify your browser", "JavaScript \u0432\u0435\u0440\u0438\u0444\u0438\u043a\u0430\u0446\u0438\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0431\u0440\u0430\u0443\u0437\u0435\u0440\u0430"} {
		if !isVerificationPage(body) {
			t.Fatal("known verification page not detected")
		}
	}
	if isVerificationPage(editorHTML+verificationHTML) || isVerificationPage("ordinary JavaScript page") {
		t.Fatal("ordinary document mistaken for verification page")
	}
}

func TestBootstrapRecognizesOnlyKnownNonSuccessChallenges(t *testing.T) {
	for _, status := range []int{http.StatusForbidden, http.StatusTooManyRequests} {
		calls := 0
		b := newDocumentBootstrap(context.Background(), func(context.Context) ([]BrowserCookie, error) {
			calls++
			return anonymousCookies(), nil
		})
		b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
			if _, err := req.Cookie("temporary"); err == nil {
				return bootstrapResponse(req, editorHTML), nil
			}
			response := bootstrapResponse(req, verificationHTML)
			response.StatusCode = status
			return response, nil
		})
		if _, err := b.fetch(testDocumentURL); err != nil || calls != 1 {
			t.Fatal("recognized non-200 challenge did not request verification")
		}
		b.close()
	}
	b := newDocumentBootstrap(context.Background(), func(context.Context) ([]BrowserCookie, error) {
		t.Fatal("ordinary HTTP access denial launched a browser")
		return nil, ErrBrowserSession
	})
	defer b.close()
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		response := bootstrapResponse(req, "document not shared")
		response.StatusCode = http.StatusForbidden
		return response, nil
	})
	if _, err := b.fetch(testDocumentURL); !errors.Is(err, ErrDocumentAccess) {
		t.Fatal("ordinary denial not preserved")
	}
}

func TestBootstrapUsesAndReusesAnonymousSession(t *testing.T) {
	calls, requests := 0, 0
	b := newDocumentBootstrap(context.Background(), func(context.Context) ([]BrowserCookie, error) {
		calls++
		return anonymousCookies(), nil
	})
	defer b.close()
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		body := verificationHTML
		if cookie, _ := req.Cookie("temporary"); cookie != nil && cookie.Value == "opaque" {
			body = editorHTML
		}
		return bootstrapResponse(req, body), nil
	})
	for i := 0; i < 2; i++ {
		if body, err := b.fetch(testDocumentURL); err != nil || body != editorHTML {
			t.Fatal("browser session failed to unlock native HTTP request")
		}
	}
	if calls != 1 || requests != 3 {
		t.Fatal("session was not reused")
	}
	if b.cookiesFor("https://office.yandex.ru") != "temporary=opaque" || b.cookiesFor("https://attacker.example") != "" {
		t.Fatal("cookie forwarding did not honor origin scope")
	}
	jar, _ := cookiejar.New(nil)
	u, _ := url.Parse(testDocumentURL)
	jar.SetCookies(u, []*http.Cookie{{Name: "hostonly", Value: "opaque", Secure: true, Path: "/"}})
	b.client.Jar = jar
	if b.cookiesFor("https://office.yandex.ru") != "" {
		t.Fatal("host-only document cookie leaked to a different host")
	}
}

func TestBootstrapFailureBackoffAndRenewal(t *testing.T) {
	now := time.Now()
	calls, requests := 0, 0
	b := newDocumentBootstrap(context.Background(), func(context.Context) ([]BrowserCookie, error) {
		calls++
		return nil, errors.New("private-provider-error")
	})
	defer b.close()
	b.now = func() time.Time { return now }
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		return bootstrapResponse(req, verificationHTML), nil
	})
	if _, err := b.fetch(testDocumentURL); !errors.Is(err, ErrBrowserSession) {
		t.Fatal("provider error not sanitized")
	}
	b.fetch(testDocumentURL)
	if calls != 1 || requests != 1 {
		t.Fatal("failure backoff ignored")
	}
	now = now.Add(61 * time.Second)
	b.fetch(testDocumentURL)
	now = now.Add(61 * time.Second)
	b.fetch(testDocumentURL)
	if calls != 2 {
		t.Fatal("rolling verification budget ignored")
	}
	now = now.Add(5 * time.Minute)
	b.fetch(testDocumentURL)
	if calls != 3 {
		t.Fatal("verification incorrectly limited to two attempts per process lifetime")
	}
}

func TestBootstrapWithoutProviderAndCancelledProvider(t *testing.T) {
	b := newDocumentBootstrap(context.Background(), nil)
	defer b.close()
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) { return bootstrapResponse(req, verificationHTML), nil })
	if _, err := b.fetch(testDocumentURL); !errors.Is(err, ErrBrowserVerification) {
		t.Fatal("missing browser provider was hidden")
	}
	select {
	case err := <-b.errors:
		if !errors.Is(err, ErrBrowserVerification) {
			t.Fatal("wrong failure kind")
		}
	default:
		t.Fatal("missing browser failure signal")
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := newDocumentBootstrap(ctx, func(context.Context) ([]BrowserCookie, error) {
		cancel()
		return anonymousCookies(), nil
	})
	defer c.close()
	c.client.Transport = b.client.Transport
	if _, err := c.fetch(testDocumentURL); !errors.Is(err, context.Canceled) {
		t.Fatal("cancelled verification accepted cookies")
	}
}

func TestBootstrapFailureStagesDoNotExposePrivateData(t *testing.T) {
	for _, test := range []struct {
		name     string
		provider BrowserCookieProvider
		retry    bootstrapRoundTripper
		stage    string
		requests int
	}{
		{"provider", func(context.Context) ([]BrowserCookie, error) {
			return nil, errors.New("private-provider-error " + testDocumentURL)
		}, nil, "provider", 1},
		{"provider timeout", func(context.Context) ([]BrowserCookie, error) {
			return nil, context.DeadlineExceeded
		}, nil, "provider_timeout", 1},
		{"invalid cookies", func(context.Context) ([]BrowserCookie, error) {
			cookies := anonymousCookies()
			cookies[0].Value = "private-cookie\r\n"
			return cookies, nil
		}, nil, "cookies", 1},
		{"retry HTTPS error", nil, func(*http.Request) (*http.Response, error) {
			return nil, errors.New("private-HTTPS-error " + testDocumentURL)
		}, "retry_https", 2},
		{"retry HTTP denial", nil, func(req *http.Request) (*http.Response, error) {
			response := bootstrapResponse(req, "private-denial-body")
			response.StatusCode = http.StatusForbidden
			return response, nil
		}, "retry_https", 2},
		{"repeated challenge", nil, func(req *http.Request) (*http.Response, error) {
			return bootstrapResponse(req, verificationHTML+"private-challenge-body"), nil
		}, "verification_repeated", 2},
	} {
		t.Run(test.name, func(t *testing.T) {
			provider := test.provider
			if provider == nil {
				provider = func(context.Context) ([]BrowserCookie, error) { return anonymousCookies(), nil }
			}
			b := newDocumentBootstrap(context.Background(), provider)
			defer b.close()
			requests := 0
			b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
				requests++
				if requests == 1 {
					return bootstrapResponse(req, verificationHTML), nil
				}
				if test.retry == nil {
					t.Fatal("failed provider or invalid cookies triggered another HTTPS request")
				}
				return test.retry(req)
			})
			_, err := b.fetch(testDocumentURL)
			want := ErrBrowserSession.Error() + " (stage=" + test.stage + ")"
			if !errors.Is(err, ErrBrowserSession) || err.Error() != want || requests != test.requests {
				t.Fatal("wrong fixed failure stage or request count")
			}
			select {
			case published := <-b.errors:
				if !errors.Is(published, ErrBrowserSession) || published.Error() != want {
					t.Fatal("failure event did not preserve the sanitized stage")
				}
			default:
				t.Fatal("failure event was not published")
			}
			if _, err := b.fetch(testDocumentURL); !errors.Is(err, errBrowserBackoff) || requests != test.requests {
				t.Fatal("backoff did not stop another request")
			}
		})
	}
}

func TestProductionDocumentPolicyAndErrorRedaction(t *testing.T) {
	for name, body := range map[string]string{
		"invalid JSON":     `<script id="client-config">private-token</script>`,
		"foreign balancer": strings.Replace(editorHTML, "office.yandex.ru", "attacker.example", 1),
		"read only":        strings.Replace(editorHTML, `"edit":true`, `"edit":false`, 1),
		"missing editor":   "private document not available",
	} {
		t.Run(name, func(t *testing.T) {
			tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
			defer tpt.Stop()
			tpt.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) { return bootstrapResponse(req, body), nil })
			if _, err := tpt.fetchDocInfo(testDocumentURL, "test-user"); !errors.Is(err, ErrDocumentAccess) {
				t.Fatal("unsafe document accepted or private parser details exposed")
			}
		})
	}
	tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	defer tpt.Stop()
	tpt.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) { return bootstrapResponse(req, editorHTML), nil })
	if _, err := tpt.fetchDocInfo(testDocumentURL, "test-user"); err != nil {
		t.Fatal("valid production editor rejected")
	}
	if _, err := tpt.fetchDocInfo("https://DOCS.YANDEX.RU/docs/test", "test-user"); err != nil {
		t.Fatal("uppercase-host production profile rejected")
	}
}

func TestTransportStopCancelsBrowserVerification(t *testing.T) {
	tpt := NewYandexDocsTransport(testDocumentURL, transport.DefaultConfig())
	started := make(chan struct{})
	if err := tpt.ConfigureBrowserBootstrap(context.Background(), func(ctx context.Context) ([]BrowserCookie, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}); err != nil {
		t.Fatal(err)
	}
	tpt.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		return bootstrapResponse(req, verificationHTML), nil
	})
	result := make(chan error, 1)
	go func() { result <- tpt.CheckDocumentAccess() }()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("verification did not begin")
	}
	if err := tpt.Stop(); err != nil || tpt.IsConnected() || tpt.IsRunning() {
		t.Fatal("transport did not stop cleanly")
	}
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal("transport stop did not cancel provider context")
		}
	case <-time.After(time.Second):
		t.Fatal("verification outlived transport stop")
	}
}
