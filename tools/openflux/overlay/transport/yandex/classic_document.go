// SPDX-License-Identifier: GPL-3.0-or-later
package yandex

import (
	"bytes"
	"encoding/json"
	"io"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/net/html"
)

const classicDocumentLimit = 24576

// ClassicDocument is a short-lived browser result, never persisted in profiles.
type ClassicDocument struct {
	SourceURL   string `json:"source_url"`
	CapturedAt  int64  `json:"captured_at"`
	BalancerURL string `json:"balancer_url"`
	Token       string `json:"token"`
	DocumentKey string `json:"document_key"`
	FileType    string `json:"file_type"`
	DownloadURL string `json:"download_url"`
	Title       string `json:"title"`
	Edit        bool   `json:"edit"`
	encodedSize int
}

func (value *ClassicDocument) UnmarshalJSON(raw []byte) error {
	if len(raw) == 0 || len(raw) > classicDocumentLimit || !utf8.Valid(raw) {
		return ErrBrowserSession
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	if token, err := d.Token(); err != nil || token != json.Delim('{') {
		return ErrBrowserSession
	}
	parsed := ClassicDocument{encodedSize: len(raw)}
	stringsByName := map[string]*string{
		"source_url": &parsed.SourceURL, "balancer_url": &parsed.BalancerURL,
		"token": &parsed.Token, "document_key": &parsed.DocumentKey,
		"file_type": &parsed.FileType, "download_url": &parsed.DownloadURL, "title": &parsed.Title,
	}
	seen := make(map[string]bool)
	for d.More() {
		key, err := d.Token()
		name, ok := key.(string)
		if err != nil || !ok || seen[name] {
			return ErrBrowserSession
		}
		seen[name] = true
		var field json.RawMessage
		if d.Decode(&field) != nil {
			return ErrBrowserSession
		}
		if target, ok := stringsByName[name]; ok {
			if len(field) == 0 || field[0] != '"' || !validJSONStringUnicode(field) || json.Unmarshal(field, target) != nil {
				return ErrBrowserSession
			}
		} else {
			switch name {
			case "captured_at":
				if len(field) == 0 || field[0] < '0' || field[0] > '9' || json.Unmarshal(field, &parsed.CapturedAt) != nil {
					return ErrBrowserSession
				}
			case "edit":
				if string(field) != "true" {
					return ErrBrowserSession
				}
				parsed.Edit = true
			default:
				return ErrBrowserSession
			}
		}
	}
	if token, err := d.Token(); err != nil || token != json.Delim('}') || len(seen) != 9 {
		return ErrBrowserSession
	}
	if _, err := d.Token(); err != io.EOF || !allowedDocumentURL(parsed.SourceURL) || !validClassicDocumentFields(&parsed) {
		return ErrBrowserSession
	}
	*value = parsed
	return nil
}

// ValidateClassicDocument binds the result to the exact requested URL and time.
func ValidateClassicDocument(document string, value *ClassicDocument, now time.Time) error {
	if value == nil || value.SourceURL != document || !allowedDocumentURL(value.SourceURL) ||
		value.CapturedAt < now.Unix()-120 || value.CapturedAt > now.Unix()+5 || !validClassicDocumentFields(value) {
		return ErrBrowserSession
	}
	if value.encodedSize == 0 {
		var encoded bytes.Buffer
		encoder := json.NewEncoder(&encoded)
		encoder.SetEscapeHTML(false)
		if encoder.Encode(value) != nil || encoded.Len()-1 > classicDocumentLimit {
			return ErrBrowserSession
		}
	}
	return nil
}

func validClassicDocumentFields(value *ClassicDocument) bool {
	if value == nil || !value.Edit || !allowedBalancer(value.BalancerURL) || !visibleASCII(value.Token, 16384) ||
		!visibleASCII(value.DocumentKey, 512) || value.DocumentKey == "." || value.DocumentKey == ".." ||
		len(value.FileType) == 0 || len(value.FileType) > 32 || !allowedDownloadURL(value.DownloadURL) ||
		len(value.Title) > 1024 || !utf8.ValidString(value.Title) {
		return false
	}
	for _, ch := range value.FileType {
		if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '.' || ch == '_' || ch == '-') {
			return false
		}
	}
	for _, ch := range value.Title {
		if unicode.IsControl(ch) {
			return false
		}
	}
	return true
}

func visibleASCII(value string, limit int) bool {
	if len(value) == 0 || len(value) > limit {
		return false
	}
	for i := range value {
		if value[i] < 0x21 || value[i] > 0x7e {
			return false
		}
	}
	return true
}

// encoding/json replaces unpaired UTF-16 escapes; reject them instead of
// changing the browser's URL, title, or token during the handoff.
func validJSONStringUnicode(raw []byte) bool {
	for i := 1; i < len(raw)-1; i++ {
		if raw[i] != '\\' {
			continue
		}
		i++
		if i >= len(raw)-1 {
			return false
		}
		if raw[i] != 'u' {
			continue
		}
		if i+4 >= len(raw) {
			return false
		}
		first, err := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
		if err != nil || first >= 0xdc00 && first <= 0xdfff {
			return false
		}
		i += 4
		if first < 0xd800 || first > 0xdbff {
			continue
		}
		if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
			return false
		}
		second, err := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
		if err != nil || second < 0xdc00 || second > 0xdfff {
			return false
		}
		i += 6
	}
	return true
}

func checkedHTTPSURL(raw string) (*url.URL, bool) {
	if len(raw) == 0 || len(raw) > 8192 || !utf8.ValidString(raw) || strings.ContainsAny(raw, "\\#") {
		return nil, false
	}
	for _, ch := range raw {
		if unicode.IsSpace(ch) || unicode.IsControl(ch) {
			return nil, false
		}
	}
	u, err := url.Parse(raw)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Opaque != "" || u.User != nil || u.Fragment != "" {
		return nil, false
	}
	if _, err := url.QueryUnescape(u.RawQuery); err != nil {
		return nil, false
	}
	host := u.Hostname()
	if !asciiDNSName(host) || (u.Host != host && u.Host != host+":443") {
		return nil, false
	}
	return u, true
}

func asciiDNSName(host string) bool {
	if len(host) == 0 || len(host) > 253 {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for i := range label {
			ch := label[i]
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
				return false
			}
		}
	}
	return true
}

// Yandex's balancer names include underscores. This origin-only exception must
// not loosen document navigation, download URLs, or arbitrary HTTP targets.
func checkedBalancerOrigin(raw string) (*url.URL, bool) {
	if len(raw) == 0 || len(raw) > 8192 || !utf8.ValidString(raw) || strings.ContainsAny(raw, "\\#") {
		return nil, false
	}
	for _, ch := range raw {
		if unicode.IsSpace(ch) || unicode.IsControl(ch) {
			return nil, false
		}
	}
	u, err := url.Parse(raw)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Opaque != "" || u.User != nil ||
		u.Path != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return nil, false
	}
	host := u.Hostname()
	if len(host) == 0 || len(host) > 253 || (u.Host != host && u.Host != host+":443") {
		return nil, false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return nil, false
		}
		for i := range label {
			ch := label[i]
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '_') {
				return nil, false
			}
		}
	}
	return u, true
}

func yandexSubdomain(host string) bool {
	host = strings.ToLower(host)
	return strings.HasSuffix(host, ".yandex.ru") || strings.HasSuffix(host, ".yandex.net")
}

func allowedDownloadURL(raw string) bool {
	u, ok := checkedHTTPSURL(raw)
	return ok && yandexSubdomain(u.Hostname()) || allowedInternalDocumentReference(raw)
}

// This provider reference is forwarded verbatim in OpenCmd, never fetched by
// the client. Do not use this policy for HTTP, browser, or WebSocket targets.
func allowedInternalDocumentReference(raw string) bool {
	const prefix = "http://localhost:12701/disk/"
	if !strings.HasPrefix(raw, prefix) || len(raw) > 8192 || !utf8.ValidString(raw) || strings.ContainsAny(raw, "\\#") {
		return false
	}
	for _, ch := range raw {
		if unicode.IsSpace(ch) || unicode.IsControl(ch) {
			return false
		}
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "http" || u.Host != "localhost:12701" || u.User != nil || u.Opaque != "" ||
		!strings.HasPrefix(u.Path, "/disk/") || len(u.Path) <= len("/disk/") || !utf8.ValidString(u.Path) || strings.ContainsAny(u.Path, "\\%?#") {
		return false
	}
	if _, err := url.QueryUnescape(u.RawQuery); err != nil {
		return false
	}
	for _, ch := range u.Path {
		if unicode.IsSpace(ch) || unicode.IsControl(ch) {
			return false
		}
	}
	for _, segment := range strings.Split(u.Path, "/") {
		if segment == "." || segment == ".." {
			return false
		}
	}
	return true
}

func allowedDocumentURL(raw string) bool {
	u, ok := checkedHTTPSURL(raw)
	return ok && (strings.EqualFold(u.Hostname(), "docs.yandex.ru") || strings.EqualFold(u.Hostname(), "disk.yandex.ru"))
}

// Parse HTML with browser quoting/case rules, excluding inert template content.
// Duplicate matching scripts fail; duplicate attributes use the first value.
func clientConfigScript(body string) (script string, found bool, err error) {
	if len(body) > 4*1024*1024 {
		return "", false, ErrDocumentAccess
	}
	root, err := html.Parse(strings.NewReader(body))
	if err != nil {
		return "", false, ErrDocumentAccess
	}
	var visit func(*html.Node) error
	visit = func(node *html.Node) error {
		if node.Type == html.ElementNode && node.Namespace == "" && node.Data == "template" {
			return nil
		}
		if node.Type == html.ElementNode && node.Namespace == "" && node.Data == "script" {
			match := false
			for _, attr := range node.Attr {
				if attr.Namespace == "" && attr.Key == "id" {
					match = attr.Val == "client-config"
					break
				}
			}
			if match {
				if found {
					return ErrDocumentAccess
				}
				found = true
				var text strings.Builder
				for child := node.FirstChild; child != nil; child = child.NextSibling {
					if child.Type != html.TextNode {
						return ErrDocumentAccess
					}
					text.WriteString(child.Data)
				}
				script = text.String()
			}
		}
		for child := node.FirstChild; child != nil; child = child.NextSibling {
			if err := visit(child); err != nil {
				return err
			}
		}
		return nil
	}
	if err := visit(root); err != nil || !found {
		return "", found, ErrDocumentAccess
	}
	return script, true, nil
}

func classicDocumentInfo(value *ClassicDocument, userID string) YandexDocsInfo {
	u, _ := url.Parse(value.BalancerURL)
	return YandexDocsInfo{
		Token: value.Token, DocID: value.DocumentKey, Origin: value.BalancerURL, Host: u.Host,
		WsURL:       "wss://" + u.Host + "/2024.1.1-375/doc/" + url.PathEscape(value.DocumentKey) + "/c/?EIO=4&transport=websocket",
		Permissions: map[string]interface{}{"edit": true},
		OpenCmd: map[string]interface{}{
			"c": "open", "id": value.DocumentKey, "userid": userID,
			"format": value.FileType, "url": value.DownloadURL, "title": value.Title, "lcid": 25,
		},
	}
}
