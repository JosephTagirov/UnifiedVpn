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
		"unknown nested":   strings.Replace(valid, `"name":`, `"unknown":true,"name":`, 1),
		"two documents":    valid + valid,
		"mixed result":     strings.Replace(valid, `"id":`, `"error":"verification_failed","id":`, 1),
		"untrusted error":  `{"id":"` + id + `","error":"private-url-or-key"}`,
		"overlong":         strings.Repeat(" ", browserReplyLimit) + valid,
		"deep":             strings.Repeat("[", 10) + "0" + strings.Repeat("]", 10),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseBrowserReply([]byte(input)); err != errBrowserReply {
				t.Fatal("malformed or ambiguous reply accepted")
			}
		})
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
		if err := encoder.Encode(browserReply{ID: strings.Repeat("0", 32), Error: "verification_failed"}); err != nil {
			peer <- err
			return
		}
		peer <- encoder.Encode(browserReply{ID: parts[1], Cookies: []yandex.BrowserCookie{{Name: "temporary", Value: "opaque", Path: "/", Secure: true}}})
	}()
	cookies, err := b.Cookies(ctx)
	if err != nil || len(cookies) != 1 || cookies[0].Name != "temporary" {
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
