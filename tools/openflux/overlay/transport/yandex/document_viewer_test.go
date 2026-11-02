// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
)

const publicViewerHTML = `<html><a href="https://passport.yandex.ru/">Sign in</a><script id="store-prefetch" type="application/json">{"rootResourceId":"synthetic-resource","resources":{"synthetic-resource":{"type":"file","meta":{"rights":["write"],"isMandatoryLoginPassthrough":true}}}}</script></html>`

func TestDocumentViewerDoesNotImplyLoginOrEditorAccess(t *testing.T) {
	if !isDocumentViewer(publicViewerHTML) || isVerificationPage(publicViewerHTML) {
		t.Fatal("public viewer misclassified")
	}
	if _, err := parseDocumentInfo(publicViewerHTML, "synthetic-user"); !errors.Is(err, ErrDocumentViewer) {
		t.Fatal("classic parser did not identify file viewer", err)
	}
	if _, err := parseVolgaDocument(publicViewerHTML); !errors.Is(err, ErrDocumentViewer) {
		t.Fatal("Volga parser did not identify file viewer", err)
	}
	for _, classic := range []bool{true, false} {
		calls := 0
		b := newDocumentBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
			calls++
			return nil, errors.New("provider should not be called")
		})
		b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
			return bootstrapResponse(req, publicViewerHTML), nil
		})
		_, err := b.fetchDocument(testDocumentURL, classic)
		if !errors.Is(err, ErrDocumentViewer) || calls != 0 {
			t.Fatal("viewer incorrectly opened browser verification", err)
		}
		b.close()
	}
}

func TestDocumentViewerRequiresAnActualStructuredFilePage(t *testing.T) {
	for _, body := range []string{
		"ordinary page with store-prefetch and a sign-in link",
		"<template>" + publicViewerHTML + "</template>",
		publicViewerHTML + publicViewerHTML,
		strings.Replace(publicViewerHTML, `"rootResourceId":"synthetic-resource"`, `"rootResourceId":"other"`, 1),
		strings.Replace(publicViewerHTML, `"type":"file"`, `"type":"dir"`, 1),
		strings.Replace(publicViewerHTML, `"resources":`, `"resources":null,"invalid":`, 1),
		publicViewerHTML + editorHTML,
		publicViewerHTML + `<script id="client-config">invalid</script>`,
	} {
		if isDocumentViewer(body) {
			t.Fatal("non-viewer page accepted as a file viewer")
		}
	}
}

func TestVerifiedBrowserRetryCanStillReturnFileViewer(t *testing.T) {
	requests := 0
	b := newDocumentBootstrap(context.Background(), func(context.Context) (*BrowserSession, error) {
		return &BrowserSession{Cookies: anonymousCookies()}, nil
	})
	defer b.close()
	b.client.Transport = bootstrapRoundTripper(func(req *http.Request) (*http.Response, error) {
		requests++
		if requests == 1 {
			return bootstrapResponse(req, verificationHTML), nil
		}
		return bootstrapResponse(req, publicViewerHTML), nil
	})
	if _, err := b.fetch(testDocumentURL); !errors.Is(err, ErrDocumentViewer) || requests != 2 {
		t.Fatal("post-verification viewer was treated as an editor", err)
	}
}
