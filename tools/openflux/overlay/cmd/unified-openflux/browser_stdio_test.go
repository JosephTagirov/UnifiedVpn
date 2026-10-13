// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"openflux/transport/yandex"
)

func TestBrowserReplyParsing(t *testing.T) {
	id := strings.Repeat("a", 32)
	valid := `{"id":"` + id + `","cookies":[{"name":"temporary","value":"opaque","domain":".yandex.ru","path":"/","secure":true,"expires":0}]}`
	if reply, err := parseBrowserReply([]byte(valid)); err != nil || len(reply.Cookies) != 1 {
		t.Fatal("valid cookie response rejected")
	}
	matched := strings.Replace(valid, `"id":`, `"user_agent":"SyntheticBrowser/1.0","id":`, 1)
	if reply, err := parseBrowserReply([]byte(matched)); err != nil || reply.UserAgent != "SyntheticBrowser/1.0" {
		t.Fatal("matched browser identity rejected")
	}
	for _, agent := range []string{`null`, `""`, `123`, `true`, `[]`, `" leading"`, `"trailing "`, `"a\r\nb"`, `"a\tb"`, `"a\u007fb"`, `"a\u0080b"`, `"` + strings.Repeat("a", 1025) + `"`} {
		input := strings.Replace(valid, `"id":`, `"user_agent":`+agent+`,"id":`, 1)
		if _, err := parseBrowserReply([]byte(input)); err != errBrowserReply {
			t.Fatal("invalid browser identity accepted")
		}
	}
	if reply, err := parseBrowserReply([]byte(`{"id":"` + id + `","error":"verification_failed"}`)); err != nil || reply.Error == "" {
		t.Fatal("safe failure response rejected")
	}
	for name, input := range map[string]string{
		"empty": "", "null": "null", "array": "[]", "missing cookies": `{"id":"` + id + `"}`,
		"bad id":           strings.Replace(valid, id, "not-an-id", 1),
		"uppercase id":     strings.Replace(valid, id, strings.ToUpper(id), 1),
		"unknown field":    strings.Replace(valid, `"id":`, `"url":"private","id":`, 1),
		"duplicate id":     strings.Replace(valid, `"id":`, `"id":"`+id+`","id":`, 1),
		"duplicate nested": strings.Replace(valid, `"name":`, `"name":"first","name":`, 1),
		"duplicate agent":  strings.Replace(matched, `"id":`, `"user_agent":"OtherBrowser/2.0","id":`, 1),
		"agent on failure": `{"id":"` + id + `","error":"verification_failed","user_agent":"SyntheticBrowser/1.0"}`,
		"unknown nested":   strings.Replace(valid, `"name":`, `"unknown":true,"name":`, 1),
		"two documents":    valid + valid,
		"mixed result":     strings.Replace(valid, `"id":`, `"error":"verification_failed","id":`, 1),
		"untrusted error":  `{"id":"` + id + `","error":"private-url-or-key"}`,
		"overlong":         strings.Repeat(" ", browserReplyLimit) + valid,
		"deep":             strings.Repeat("[", 10) + "0" + strings.Repeat("]", 10),
		"case alias":       strings.Replace(valid, `"id":`, `"ID":`, 1),
		"null cookie":      strings.Replace(valid, `"expires":0`, `"expires":null`, 1),
		"quoted expiry":    strings.Replace(valid, `"expires":0`, `"expires":"0"`, 1),
		"fraction expiry":  strings.Replace(valid, `"expires":0`, `"expires":0.0`, 1),
		"null value":       strings.Replace(valid, `"value":"opaque"`, `"value":null`, 1),
		"null domain":      strings.Replace(valid, `"domain":".yandex.ru"`, `"domain":null`, 1),
		"missing domain":   strings.Replace(valid, `"domain":".yandex.ru",`, ``, 1),
		"secure string":    strings.Replace(valid, `"secure":true`, `"secure":"true"`, 1),
		"cookie case":      strings.Replace(valid, `"secure":true`, `"Secure":true`, 1),
		"empty error":      `{"id":"` + id + `","error":""}`,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseBrowserReply([]byte(input)); err != errBrowserReply {
				t.Fatal("malformed or ambiguous reply accepted")
			}
		})
	}
}

func browserClassicFixture() *yandex.ClassicDocument {
	return &yandex.ClassicDocument{SourceURL: "https://docs.yandex.ru/docs/synthetic", CapturedAt: time.Now().Unix(),
		BalancerURL: "https://office.yandex.ru", Token: "synthetic-token", DocumentKey: "synthetic-key",
		FileType: "docx", DownloadURL: "https://download.yandex.net/file?token=synthetic", Title: "", Edit: true}
}

func classicReplyFixture(id string) browserReply {
	return browserReply{ID: id, Cookies: []yandex.BrowserCookie{{Name: "temporary", Value: "opaque", Domain: ".yandex.ru", Path: "/", Secure: true}},
		UserAgent: "SyntheticBrowser/1.0", ClassicDocument: browserClassicFixture()}
}

func TestBrowserReplyClassicDocumentParsing(t *testing.T) {
	raw, _ := json.Marshal(classicReplyFixture(strings.Repeat("a", 32)))
	reply, err := parseBrowserReply(raw)
	if err != nil || reply.ClassicDocument == nil || reply.ClassicDocument.DocumentKey != "synthetic-key" ||
		yandex.ValidateClassicDocument("https://docs.yandex.ru/docs/synthetic", reply.ClassicDocument, time.Now()) != nil {
		t.Fatal("valid typed classic response rejected")
	}
	var fields map[string]json.RawMessage
	_ = json.Unmarshal(raw, &fields)
	for name, metadata := range map[string]string{
		"null": "null", "array": "[]", "string": `"private-token"`, "empty": `{}`,
		"duplicate": strings.Replace(string(fields["classic_document"]), `"title":`, `"title":"duplicate","title":`, 1),
		"unknown":   strings.Replace(string(fields["classic_document"]), `"title":`, `"unknown":0,"title":`, 1),
		"numeric":   strings.Replace(string(fields["classic_document"]), `"title":""`, `"title":1`, 1),
		"read only": strings.Replace(string(fields["classic_document"]), `"edit":true`, `"edit":false`, 1),
		"oversized": string(fields["classic_document"][:len(fields["classic_document"])-1]) + strings.Repeat(" ", 24576) + "}",
	} {
		t.Run(name, func(t *testing.T) {
			input := strings.Replace(string(raw), string(fields["classic_document"]), metadata, 1)
			if _, err := parseBrowserReply([]byte(input)); err != errBrowserReply {
				t.Fatal("invalid optional metadata downgraded to cookie-only success")
			}
		})
	}
	for _, input := range []string{
		strings.Replace(string(raw), `"cookies":`+string(fields["cookies"])+`,`, ``, 1),
		strings.Replace(string(raw), `"id":`, `"error":"verification_failed","id":`, 1),
		strings.Replace(string(raw), `"classic_document":`, `"Classic_Document":`, 1),
		strings.Replace(string(raw), `"title":""`, "\"title\":\""+string([]byte{0xff})+"\"", 1),
	} {
		if _, err := parseBrowserReply([]byte(input)); err != errBrowserReply {
			t.Fatal("ambiguous classic response accepted")
		}
	}
	for _, size := range []int{browserReplyLimit, browserReplyLimit + 1} {
		input := string(raw[:len(raw)-1]) + strings.Repeat(" ", size-len(raw)) + "}"
		if _, err := parseBrowserReply([]byte(input)); (err == nil) != (size == browserReplyLimit) {
			t.Fatal("full response UTF-8 byte limit changed")
		}
	}
}

func TestBrowserPipeClassicDocumentIsNonceBound(t *testing.T) {
	input, writer := io.Pipe()
	requests, output := io.Pipe()
	defer writer.Close()
	defer requests.Close()
	defer output.Close()
	b := newStdioBrowserBootstrap(input, output)
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	peer := make(chan error, 1)
	go func() {
		line, err := bufio.NewReader(requests).ReadString('\n')
		parts := strings.Fields(line)
		if err != nil || len(parts) != 2 || !validBrowserRequestID(parts[1]) {
			peer <- errors.New("invalid control request")
			return
		}
		encoder := json.NewEncoder(writer)
		stale := classicReplyFixture(strings.Repeat("0", 32))
		stale.ClassicDocument.DocumentKey = "stale-key"
		if err := encoder.Encode(stale); err != nil {
			peer <- err
			return
		}
		peer <- encoder.Encode(classicReplyFixture(parts[1]))
	}()
	session, err := b.Cookies(ctx)
	if err != nil || session == nil || session.ClassicDocument == nil || session.ClassicDocument.DocumentKey != "synthetic-key" {
		t.Fatal("stale nonce satisfied classic document request")
	}
	if err := <-peer; err != nil {
		t.Fatal(err)
	}
}

func TestBrowserPipeRequestAndStaleReply(t *testing.T) {
	input, writer := io.Pipe()
	requests, output := io.Pipe()
	defer writer.Close()
	defer requests.Close()
	defer output.Close()
	b := newStdioBrowserBootstrap(input, output)
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	peer := make(chan error, 1)
	go func() {
		line, err := bufio.NewReader(requests).ReadString('\n')
		parts := strings.Fields(line)
		if err != nil || len(parts) != 2 || parts[0] != "OPENFLUX_BROWSER_VERIFY" || !validBrowserRequestID(parts[1]) {
			peer <- errors.New("invalid control request")
			return
		}
		encoder := json.NewEncoder(writer)
		if err := encoder.Encode(browserReply{ID: strings.Repeat("0", 32), Cookies: []yandex.BrowserCookie{{Name: "stale", Value: "stale"}}, UserAgent: "StaleBrowser/0.1"}); err != nil {
			peer <- err
			return
		}
		peer <- encoder.Encode(browserReply{ID: parts[1], Cookies: []yandex.BrowserCookie{{Name: "temporary", Value: "opaque", Path: "/", Secure: true}}, UserAgent: "SyntheticBrowser/1.0"})
	}()
	cookies, err := b.Cookies(ctx)
	if err != nil || cookies == nil || len(cookies.Cookies) != 1 || cookies.Cookies[0].Name != "temporary" || cookies.UserAgent != "SyntheticBrowser/1.0" {
		t.Fatal("matching browser response not delivered")
	}
	if err := <-peer; err != nil {
		t.Fatal(err)
	}
}

func TestBrowserPipeCancellationAndClose(t *testing.T) {
	input, writer := io.Pipe()
	defer writer.Close()
	var output bytes.Buffer
	b := newStdioBrowserBootstrap(input, &output)
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := b.Cookies(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("cancelled browser request did not exit")
	}
	b.Close()
	if _, err := b.Cookies(context.Background()); err != errBrowserReply {
		t.Fatal("closed pipe accepted a request")
	}
}

func TestBrowserPipeOversizedInputHasNoEcho(t *testing.T) {
	b := newStdioBrowserBootstrap(io.NopCloser(strings.NewReader(strings.Repeat("secret", browserReplyLimit)+"\n")), io.Discard)
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := b.Cookies(ctx); err != errBrowserReply || strings.Contains(fmt.Sprint(err), "secret") {
		t.Fatal("oversized reply was not safely rejected")
	}
}

func TestBrowserErrorCancelsPeerWait(t *testing.T) {
	ended := make(chan struct{})
	verification := make(chan error, 1)
	verification <- yandex.ErrBrowserSession
	err := waitPeerOrBrowserError(context.Background(), func(ctx context.Context) error {
		defer close(ended)
		<-ctx.Done()
		return ctx.Err()
	}, verification)
	if !errors.Is(err, yandex.ErrBrowserSession) {
		t.Fatal("browser failure was hidden by peer timeout")
	}
	select {
	case <-ended:
	case <-time.After(time.Second):
		t.Fatal("peer wait did not stop")
	}
}

func TestPeerWaitDeadlineKeepsStageAcrossReadySelectCases(t *testing.T) {
	for range 64 {
		ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
		err := waitPeerOrBrowserError(ctx, func(ctx context.Context) error {
			return ctx.Err()
		}, nil)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) || err != errPeerWaitTimeout || !strings.Contains(err.Error(), "stage=peer_wait") {
			t.Fatal("peer-readiness deadline lost its fixed stage")
		}
	}
}

func TestPeerWaitWorkerDeadlineKeepsStage(t *testing.T) {
	for _, waitErr := range []error{context.DeadlineExceeded, errPeerWaitTimeout} {
		err := waitPeerOrBrowserError(context.Background(), func(context.Context) error {
			return waitErr
		}, nil)
		if err != errPeerWaitTimeout {
			t.Fatal("worker deadline lost or duplicated its peer-readiness stage")
		}
	}
}

func TestPeerWaitDeadlinePreservesQueuedBrowserFailure(t *testing.T) {
	browserFailure := fmt.Errorf("%w (stage=provider_timeout)", yandex.ErrBrowserSession)
	for range 64 {
		ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
		verification := make(chan error, 1)
		verification <- browserFailure
		err := waitPeerOrBrowserError(ctx, func(ctx context.Context) error {
			return ctx.Err()
		}, verification)
		cancel()
		if err != browserFailure {
			t.Fatal("enclosing peer timeout hid an available browser failure")
		}
	}
}

func TestPeerWaitCancellationIsNotReportedAsTimeout(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	verification := make(chan error, 1)
	verification <- yandex.ErrBrowserSession
	err := waitPeerOrBrowserError(ctx, func(ctx context.Context) error {
		return ctx.Err()
	}, verification)
	if !errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "stage=") {
		t.Fatal("cancelled startup was reported as a verification failure or timeout")
	}
}

func TestPeerWaitDeadlineStopsInFlightWait(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	ended := make(chan struct{})
	err := waitPeerOrBrowserError(ctx, func(ctx context.Context) error {
		defer close(ended)
		<-ctx.Done()
		return ctx.Err()
	}, nil)
	if err != errPeerWaitTimeout {
		t.Fatal("in-flight peer deadline lost its fixed stage")
	}
	select {
	case <-ended:
	case <-time.After(time.Second):
		t.Fatal("peer wait outlived its deadline")
	}
}
