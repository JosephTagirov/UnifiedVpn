// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"openflux/transport"
)

func classicFixture(now time.Time) *ClassicDocument {
	return &ClassicDocument{SourceURL: testDocumentURL, CapturedAt: now.Unix(),
		BalancerURL: "https://office.yandex.ru", Token: "synthetic-token", DocumentKey: "synthetic-key",
		FileType: "docx", DownloadURL: "https://download.yandex.net/file?token=synthetic", Title: "Synthetic document", Edit: true}
}

func TestClientConfigStructuredHTML(t *testing.T) {
	for _, tag := range []string{
		`<script id='client-config'>`, `<SCRIPT ID="client-config">`, `<script id=client-config>`,
		"<script\n type='application/json'\nID = 'client-config'\ndata-other='>' >",
		`<script data-before="x" id="client&#45;config" data-after='y'>`,
		`<script id="client-config" id="other">`,
	} {
		body := strings.Replace(editorHTML, `<script id="client-config">`, tag, 1)
		body = strings.Replace(body, "</script>", "</SCRIPT>", 1)
		info, err := parseDocumentInfo(body, "user")
		if err != nil || info.DocID != "private-key" || isVerificationPage(body+verificationHTML) {
			t.Fatalf("valid HTML script form rejected: %s", tag)
		}
		volgaBody := strings.Replace(volgaPage(nil), `<script type="application/json" id="client-config">`, tag, 1)
		if _, err := parseVolgaDocument(volgaBody); err != nil {
			t.Fatalf("Volga parser differs for %s", tag)
		}
	}
	for name, body := range map[string]string{
		"duplicate scripts": editorHTML + editorHTML,
		"shadowed id":       strings.Replace(editorHTML, `id="client-config"`, `id="other" id="client-config"`, 1),
		"comment":           "<!--" + editorHTML + "-->",
		"not a script":      strings.ReplaceAll(editorHTML, "script", "div"),
		"template":          "<template>" + editorHTML + "</template>",
		"SVG":               "<svg>" + editorHTML + "</svg>",
		"MathML":            "<math>" + editorHTML + "</math>",
		"empty":             `<script id='client-config'></script>`,
		"invalid JSON":      `<script id='client-config'>private-token</script>`,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseDocumentInfo(body, "user"); err == nil || strings.Contains(err.Error(), "private-token") {
				t.Fatal("ambiguous or invalid config accepted or exposed")
			}
		})
	}
	if !isVerificationPage(`<!-- id="client-config" -->` + verificationHTML) {
		t.Fatal("comment suppressed browser verification")
	}
	if !isVerificationPage("<template>" + editorHTML + "</template>" + verificationHTML) {
		t.Fatal("inert template suppressed browser verification")
	}
	if _, err := parseDocumentInfo(strings.TrimSuffix(editorHTML, "</script>"), "user"); err != nil {
		t.Fatal("browser-valid end-of-file script rejected")
	}
	if _, err := parseDocumentInfo("<template>"+editorHTML+"</template>"+editorHTML, "user"); err != nil {
		t.Fatal("inert template counted as a duplicate script")
	}
}

func TestClassicDocumentURLPolicy(t *testing.T) {
	for _, raw := range []string{"https://office.yandex.ru", "HTTPS://A.B.YANDEX.NET:443"} {
		if !allowedBalancer(raw) || !allowedDownloadURL(raw) {
			t.Fatal("valid Yandex subdomain rejected")
		}
	}
	for _, raw := range []string{
		"http://office.yandex.ru", "https://yandex.ru", "https://yandex.net", "https://office.yandex.ru.attacker.invalid",
		"https://user@office.yandex.ru", "https://office.yandex.ru#", "https://office.yandex.ru#secret",
		"https://office.yandex.ru:0443", "https://office.yandex.ru:", "https://office.yandex.ru:444",
		"https://.yandex.ru", "https://a..yandex.ru", "https://-a.yandex.ru", "https://a-.yandex.ru",
		"https://a!.yandex.ru", "https://office.yandex.ru.", "https://\u00e9.yandex.ru", "https://127.0.0.1",
		"https://[::1]", "https://" + strings.Repeat("a", 64) + ".yandex.ru",
		"https://office.yandex.ru\\secret", "https://office.yandex.ru/secret value", "https://office.yandex.ru/\u00a0",
		"https://office.yandex.ru/\u0085", "https://office.yandex.ru/\x7f", "https://office.yandex.ru/\x00",
		"https://office.yandex.ru/file?token=%", "https://office.yandex.ru/file?token=%GG",
	} {
		if allowedBalancer(raw) || allowedDownloadURL(raw) {
			t.Fatal("unsafe Yandex resource accepted")
		}
	}
	for _, suffix := range []string{"/", "/path", "?", "?token=synthetic"} {
		raw := "https://office.yandex.ru" + suffix
		if allowedBalancer(raw) || !allowedDownloadURL(raw) {
			t.Fatal("origin-only and download policies were conflated")
		}
	}
	base := "https://download.yandex.net/"
	if !allowedDownloadURL(base+strings.Repeat("a", 8192-len(base))) || allowedDownloadURL(base+strings.Repeat("a", 8193-len(base))) {
		t.Fatal("URL byte limit changed")
	}
}

func TestBalancerUnderscoresAreAnOriginOnlyException(t *testing.T) {
	for _, origin := range []string{
		"https://doc_service.yandex.ru", "https://_office.yandex.net", "https://office_.yandex.ru",
		"HTTPS://_OFFICE_.YANDEX.NET:443", "https://a._office.yandex.ru",
		"https://" + strings.Repeat("_", 63) + ".yandex.ru",
	} {
		if !allowedBalancer(origin) {
			t.Fatal("provider balancer origin with underscore rejected")
		}
		if _, ok := checkedHTTPSURL(origin); ok || allowedDownloadURL(origin) || allowedDocumentURL(origin) {
			t.Fatal("balancer exception broadened another URL policy")
		}
	}
	for _, origin := range []string{
		"http://_office.yandex.ru", "https://_office.yandex.ru/", "https://_office.yandex.ru/path",
		"https://_office.yandex.ru?", "https://_office.yandex.ru?token=synthetic", "https://_office.yandex.ru#",
		"https://user@_office.yandex.ru", "https://_office.yandex.ru:0443", "https://_office.yandex.ru:444",
		"https://_office.yandex.ru:", "https://_office.yandex.ru.attacker.invalid", "https://_yandex.ru",
		"https://_office..yandex.ru", "https://_office.yandex.ru.", "https://-_office.yandex.ru", "https://_office-.yandex.ru",
		"https://_off!ce.yandex.ru", "https://_off\u00e9ce.yandex.ru", "https://_office.yandex.ru\\private",
		"https://_office.yandex.ru\n", "https://_office.yandex.ru\u00a0", "https://_office%2eyandex.ru",
		"https://" + strings.Repeat("_", 64) + ".yandex.ru",
		"https://" + strings.Repeat(strings.Repeat("_", 63)+".", 4) + "yandex.ru",
	} {
		if allowedBalancer(origin) {
			t.Fatal("unsafe balancer origin accepted")
		}
	}
}

func TestBalancerUnderscoreWorksForHTMLAndTypedBrowserResult(t *testing.T) {
	const origin = "https://doc_service.yandex.ru:443"
	now := time.Unix(1800000000, 0)
	value := classicFixture(now)
	value.BalancerURL = origin
	value.DownloadURL = "http://localhost:12701/disk/synthetic?signature=synthetic"
	requests := 0
	b := newDocumentBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
		return &BrowserSession{Cookies: anonymousCookies(), ClassicDocument: value}, nil
	})
	defer b.close()
	b.now = func() time.Time { return now }
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		return bootstrapResponse(req, verificationHTML), nil
	})
	info, err := (&YandexDocsTransport{bootstrap: b}).fetchDocInfo(testDocumentURL, "user")
	if err != nil || requests != 1 || info.Origin != origin || info.Host != "doc_service.yandex.ru:443" ||
		info.CookieStr != "temporary=opaque" || !strings.HasPrefix(info.WsURL, "wss://doc_service.yandex.ru:443/") ||
		info.OpenCmd["url"] != value.DownloadURL {
		t.Fatal("typed provider metadata with underscore origin rejected or changed")
	}
	body := strings.Replace(editorHTML, "https://office.yandex.ru", origin, 1)
	body = strings.Replace(body, "https://download.yandex.net/document?token=private", value.DownloadURL, 1)
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		return bootstrapResponse(req, body), nil
	})
	info, err = (&YandexDocsTransport{bootstrap: b}).fetchDocInfo(testDocumentURL, "user")
	if err != nil || requests != 2 || info.Origin != origin || info.OpenCmd["url"] != value.DownloadURL {
		t.Fatal("native HTML provider metadata with underscore origin rejected or changed")
	}
}

func TestInternalDocumentReferencePolicy(t *testing.T) {
	const prefix = "http://localhost:12701/disk/"
	for _, reference := range []string{
		prefix + "synthetic",
		prefix + "synthetic?signature=synthetic%2Ftoken&version=1",
		prefix + "folder/document.docx?",
		prefix + "synthetic%2Dkey?name=synthetic%20document.docx",
		prefix + "%D1%84?signature=%ff",
		prefix + strings.Repeat("a", 8192-len(prefix)),
	} {
		if !allowedInternalDocumentReference(reference) || !allowedDownloadURL(reference) {
			t.Fatal("valid opaque provider reference rejected")
		}
		if allowedDocumentURL(reference) || allowedBalancer(reference) {
			t.Fatal("opaque reference broadened a network destination policy")
		}
	}
	for _, reference := range []string{
		"http://localhost:12701/disk/", "http://localhost:12701/disk/?signature=synthetic",
		"http://localhost:12701/other/synthetic", "http://localhost:12701/diskette/synthetic",
		"https://localhost:12701/disk/synthetic", "HTTP://localhost:12701/disk/synthetic",
		"http://LOCALHOST:12701/disk/synthetic", "http://127.0.0.1:12701/disk/synthetic",
		"http://[::1]:12701/disk/synthetic", "http://localhost.:12701/disk/synthetic",
		"http://localhost.attacker.invalid:12701/disk/synthetic", "http://localhost:12702/disk/synthetic",
		"http://localhost:012701/disk/synthetic", "http://localhost/disk/synthetic",
		"http://user:password@localhost:12701/disk/synthetic", "http://localhost:12701@attacker.invalid/disk/synthetic",
		prefix + "synthetic#", prefix + "synthetic#private", prefix + "synthetic\\file",
		prefix + "../private", prefix + "folder/../../private", prefix + "./synthetic",
		prefix + "%2e%2e/private", prefix + "folder/%2E%2e/private", prefix + "%2f..%2fprivate",
		prefix + "%252e%252e/private", prefix + "folder%255c..%255cprivate",
		prefix + "%5cprivate", prefix + "%3fquery", prefix + "%23fragment",
		prefix + "%00private", prefix + "%0aprivate", prefix + "%20private", prefix + "%C2%A0private",
		prefix + "%ff", prefix + "%c0%af", prefix + "%ed%a0%80",
		prefix + "space value", prefix + "\u0080value", prefix + "\u00a0value",
		prefix + "synthetic?bad=%", prefix + "synthetic?bad=%GG", prefix + "bad%GG",
		prefix + "synthetic?bad=white space", prefix + "synthetic?bad=\x00",
		prefix + strings.Repeat("a", 8193-len(prefix)), prefix + string([]byte{0xff}),
	} {
		if allowedInternalDocumentReference(reference) || allowedDownloadURL(reference) {
			t.Fatal("unsafe internal provider reference accepted")
		}
	}
}

func TestInternalDocumentReferenceRemainsOpaqueAndIsNeverFetched(t *testing.T) {
	const reference = "http://localhost:12701/disk/synthetic%2Dkey?signature=synthetic%2Ftoken&name=sample%20document.docx"
	now := time.Unix(1800000000, 0)
	value := classicFixture(now)
	value.DownloadURL = reference
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var decoded ClassicDocument
	if json.Unmarshal(raw, &decoded) != nil || ValidateClassicDocument(testDocumentURL, &decoded, now) != nil || decoded.DownloadURL != reference {
		t.Fatal("provider reference did not survive typed JSON validation exactly")
	}
	requests := 0
	b := newDocumentBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
		return &BrowserSession{Cookies: anonymousCookies(), UserAgent: "SyntheticBrowser/1.0", ClassicDocument: &decoded}, nil
	})
	defer b.close()
	b.now = func() time.Time { return now }
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		if req.URL.String() != testDocumentURL {
			t.Fatal("opaque provider reference became a client request")
		}
		return bootstrapResponse(req, verificationHTML), nil
	})
	if _, err := b.request(reference); !errors.Is(err, ErrDocumentAccess) || requests != 0 || b.cookiesFor(reference) != "" {
		t.Fatal("provider reference accepted as an HTTP or cookie destination")
	}
	info, err := (&YandexDocsTransport{bootstrap: b}).fetchDocInfo(testDocumentURL, "user")
	if err != nil || requests != 1 || info.OpenCmd["url"] != reference || info.Host != "office.yandex.ru" ||
		!strings.HasPrefix(info.WsURL, "wss://office.yandex.ru/") {
		t.Fatal("provider reference was fetched, rewritten, or used as the WebSocket target")
	}
	encodedReference, _ := json.Marshal(reference)
	body := strings.Replace(editorHTML, `"https://download.yandex.net/document?token=private"`, string(encodedReference), 1)
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		if req.URL.String() != testDocumentURL {
			t.Fatal("native HTML reference became a client request")
		}
		return bootstrapResponse(req, body), nil
	})
	info, err = (&YandexDocsTransport{bootstrap: b}).fetchDocInfo(testDocumentURL, "user")
	if err != nil || requests != 2 || info.OpenCmd["url"] != reference {
		t.Fatal("native HTML reference was rejected, rewritten, or fetched")
	}
}

func TestClassicDocumentValidationBoundaries(t *testing.T) {
	now := time.Unix(1800000000, 0)
	for _, offset := range []int64{-120, 0, 5} {
		value := classicFixture(now)
		value.CapturedAt += offset
		if err := ValidateClassicDocument(testDocumentURL, value, now); err != nil {
			t.Fatal("valid timestamp boundary rejected")
		}
	}
	for name, mutate := range map[string]func(*ClassicDocument){
		"expired":       func(v *ClassicDocument) { v.CapturedAt -= 121 },
		"future":        func(v *ClassicDocument) { v.CapturedAt += 6 },
		"different URL": func(v *ClassicDocument) { v.SourceURL += "&other=1" },
		"normalized URL": func(v *ClassicDocument) {
			v.SourceURL = strings.Replace(v.SourceURL, "docs.yandex.ru", "DOCS.YANDEX.RU", 1)
		},
		"empty token":     func(v *ClassicDocument) { v.Token = "" },
		"token space":     func(v *ClassicDocument) { v.Token = "a b" },
		"token control":   func(v *ClassicDocument) { v.Token = "a\x7fb" },
		"token nonASCII":  func(v *ClassicDocument) { v.Token = "a\u00e9" },
		"token long":      func(v *ClassicDocument) { v.Token = strings.Repeat("a", 16385) },
		"empty key":       func(v *ClassicDocument) { v.DocumentKey = "" },
		"key space":       func(v *ClassicDocument) { v.DocumentKey = "a b" },
		"key dot":         func(v *ClassicDocument) { v.DocumentKey = "." },
		"key dotdot":      func(v *ClassicDocument) { v.DocumentKey = ".." },
		"key long":        func(v *ClassicDocument) { v.DocumentKey = strings.Repeat("a", 513) },
		"empty type":      func(v *ClassicDocument) { v.FileType = "" },
		"type path":       func(v *ClassicDocument) { v.FileType = "../docx" },
		"type long":       func(v *ClassicDocument) { v.FileType = strings.Repeat("a", 33) },
		"title control":   func(v *ClassicDocument) { v.Title = "a\nb" },
		"title C1":        func(v *ClassicDocument) { v.Title = "a\u0080b" },
		"title long":      func(v *ClassicDocument) { v.Title = strings.Repeat("\u00e9", 513) },
		"title invalid":   func(v *ClassicDocument) { v.Title = string([]byte{0xff}) },
		"read only":       func(v *ClassicDocument) { v.Edit = false },
		"balancer slash":  func(v *ClassicDocument) { v.BalancerURL += "/" },
		"download origin": func(v *ClassicDocument) { v.DownloadURL = "https://attacker.invalid/file" },
		"total size": func(v *ClassicDocument) {
			v.Token = strings.Repeat("t", 16384)
			v.DownloadURL = "https://download.yandex.net/" + strings.Repeat("d", 8100)
		},
	} {
		t.Run(name, func(t *testing.T) {
			value := classicFixture(now)
			mutate(value)
			if err := ValidateClassicDocument(testDocumentURL, value, now); !errors.Is(err, ErrBrowserSession) {
				t.Fatal("invalid metadata accepted")
			}
		})
	}
	for _, title := range []string{"", strings.Repeat("\u00e9", 512)} {
		value := classicFixture(now)
		value.Token, value.DocumentKey, value.FileType, value.Title = strings.Repeat("!", 16384), strings.Repeat("~", 512), strings.Repeat("_", 32), title
		if err := ValidateClassicDocument(testDocumentURL, value, now); err != nil {
			t.Fatal("valid scalar size boundary rejected")
		}
	}
}

func TestClassicDocumentStrictJSON(t *testing.T) {
	now := time.Unix(1800000000, 0)
	raw, _ := json.Marshal(classicFixture(now))
	var value ClassicDocument
	if json.Unmarshal(raw, &value) != nil || ValidateClassicDocument(testDocumentURL, &value, now) != nil {
		t.Fatal("valid typed metadata rejected")
	}
	var fields map[string]json.RawMessage
	_ = json.Unmarshal(raw, &fields)
	for key := range fields {
		for _, replacement := range []string{"null", "[]", "{}", `"wrong"`, "1", "1.0", "true"} {
			if strings.HasSuffix(key, "url") || key == "token" || key == "document_key" || key == "file_type" || key == "title" {
				if replacement == `"wrong"` {
					continue
				}
			}
			if key == "captured_at" && replacement == "1" || key == "edit" && replacement == "true" {
				continue
			}
			input := strings.Replace(string(raw), `"`+key+`":`+string(fields[key]), `"`+key+`":`+replacement, 1)
			if json.Unmarshal([]byte(input), &ClassicDocument{}) == nil {
				t.Fatalf("wrong JSON type accepted for %s: %s", key, replacement)
			}
		}
		copyFields := make(map[string]json.RawMessage)
		for other, field := range fields {
			if other != key {
				copyFields[other] = field
			}
		}
		missing, _ := json.Marshal(copyFields)
		if json.Unmarshal(missing, &ClassicDocument{}) == nil {
			t.Fatalf("required field omitted: %s", key)
		}
	}
	for _, input := range []string{
		"null", "[]", `{}`, string(raw) + string(raw),
		strings.Replace(string(raw), `"title":`, `"unknown":true,"title":`, 1),
		strings.Replace(string(raw), `"title":`, `"title":"duplicate","title":`, 1),
		strings.Replace(string(raw), `"title":`, `"TITLE":`, 1),
		strings.Replace(string(raw), `"edit":true`, `"edit":false`, 1),
		strings.Replace(string(raw), `"captured_at":1800000000`, `"captured_at":1e3`, 1),
		strings.Replace(string(raw), `"captured_at":1800000000`, `"captured_at":9223372036854775808`, 1),
		strings.Replace(string(raw), `"title":"Synthetic document"`, `"title":"\ud800"`, 1),
		strings.Replace(string(raw), `"title":"Synthetic document"`, `"title":"\udc00"`, 1),
		strings.Replace(string(raw), `"title":"Synthetic document"`, `"title":"\ud800\u0041"`, 1),
	} {
		if json.Unmarshal([]byte(input), &ClassicDocument{}) == nil {
			t.Fatal("ambiguous metadata accepted")
		}
	}
	paired := strings.Replace(string(raw), `"title":"Synthetic document"`, `"title":"\ud83d\ude00"`, 1)
	if json.Unmarshal([]byte(paired), &ClassicDocument{}) != nil {
		t.Fatal("valid Unicode surrogate pair rejected")
	}
	for _, size := range []int{classicDocumentLimit, classicDocumentLimit + 1} {
		input := append(append([]byte(nil), raw[:len(raw)-1]...), []byte(strings.Repeat(" ", size-len(raw)))...)
		input = append(input, '}')
		if err := json.Unmarshal(input, &ClassicDocument{}); (err == nil) != (size == classicDocumentLimit) {
			t.Fatal("metadata UTF-8 byte limit changed")
		}
	}
}

func TestClassicBrowserResultAvoidsNativeRetry(t *testing.T) {
	now := time.Unix(1800000000, 0)
	value := classicFixture(now)
	value.DocumentKey = "a/../b?percent=%25#fragment"
	calls, requests := 0, 0
	b := newDocumentBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
		calls++
		return &BrowserSession{Cookies: anonymousCookies(), UserAgent: "SyntheticBrowser/1.0", ClassicDocument: value}, nil
	})
	defer b.close()
	b.now = func() time.Time { return now }
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		return bootstrapResponse(req, verificationHTML), nil
	})
	result, err := b.fetch(testDocumentURL)
	if err != nil || result.classicDocument == nil || result.html != "" || calls != 1 || requests != 1 {
		t.Fatal("browser document did not bypass the native retry with a typed result")
	}
	tpt := &YandexDocsTransport{bootstrap: b}
	info, err := tpt.fetchDocInfo(testDocumentURL, "user")
	if err != nil || calls != 2 || requests != 2 || info.BrowserUserAgent != "SyntheticBrowser/1.0" || info.CookieStr != "temporary=opaque" ||
		info.Token != value.Token || info.DocID != value.DocumentKey || info.OpenCmd["url"] != value.DownloadURL || info.Permissions["edit"] != true {
		t.Fatal("typed browser document was cached, lost, or retried natively")
	}
	ws, err := url.Parse(info.WsURL)
	if err != nil || ws.EscapedPath() != "/2024.1.1-375/doc/"+url.PathEscape(value.DocumentKey)+"/c/" || ws.RawQuery != "EIO=4&transport=websocket" || ws.Fragment != "" {
		t.Fatal("document key escaped its WebSocket path segment")
	}
}

func TestInvalidClassicBrowserResultHasNoFallbackOrInstalledState(t *testing.T) {
	now := time.Unix(1800000000, 0)
	for name, mutate := range map[string]func(*ClassicDocument){
		"expired": func(v *ClassicDocument) { v.CapturedAt -= 121 },
		"URL":     func(v *ClassicDocument) { v.SourceURL += "&other" },
		"token":   func(v *ClassicDocument) { v.Token = "private-token\n" },
		"edit":    func(v *ClassicDocument) { v.Edit = false },
	} {
		t.Run(name, func(t *testing.T) {
			value := classicFixture(now)
			mutate(value)
			b := newDocumentBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
				return &BrowserSession{Cookies: anonymousCookies(), UserAgent: "SyntheticBrowser/1.0", ClassicDocument: value}, nil
			})
			defer b.close()
			b.now = func() time.Time { return now }
			requests := 0
			b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
				requests++
				return bootstrapResponse(req, verificationHTML), nil
			})
			_, err := b.fetch(testDocumentURL)
			u, _ := url.Parse(testDocumentURL)
			if !errors.Is(err, errBrowserDocument) || strings.Contains(err.Error(), "private") || requests != 1 || len(b.client.Jar.Cookies(u)) != 0 || b.userAgent != "" {
				t.Fatal("invalid metadata fell back to cookies, installed state, or leaked data")
			}
		})
	}
}

func TestVolgaRejectsClassicBrowserMetadata(t *testing.T) {
	for _, authorize := range []bool{false, true} {
		tp := NewYandexVolgaTransport(testDocumentURL, transport.DefaultConfig())
		if err := tp.ConfigureBrowserBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
			return &BrowserSession{Cookies: anonymousCookies(), ClassicDocument: classicFixture(time.Now())}, nil
		}); err != nil {
			t.Fatal(err)
		}
		requests := 0
		tp.bootstrap.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
			requests++
			return bootstrapResponse(req, verificationHTML), nil
		})
		var err error
		if authorize {
			_, err = tp.authorizeDocument()
		} else {
			err = tp.CheckDocumentAccess()
		}
		if !errors.Is(err, errBrowserDocument) || requests != 1 {
			t.Fatal("Volga accepted classic metadata or retried natively")
		}
		tp.Stop()
	}
}
