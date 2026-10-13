// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"unicode/utf8"

	"openflux/transport/yandex"
)

const browserReplyLimit = 32768

var errBrowserReply = errors.New("invalid browser verification response")

type browserReply struct {
	ID              string                  `json:"id"`
	Cookies         []yandex.BrowserCookie  `json:"cookies,omitempty"`
	Error           string                  `json:"error,omitempty"`
	UserAgent       browserUserAgent        `json:"user_agent,omitempty"`
	ClassicDocument *yandex.ClassicDocument `json:"classic_document,omitempty"`
}

type browserUserAgent string

func (value *browserUserAgent) UnmarshalJSON(raw []byte) error {
	var parsed string
	if json.Unmarshal(raw, &parsed) != nil || !yandex.ValidBrowserUserAgent(parsed) {
		return errBrowserReply
	}
	*value = browserUserAgent(parsed)
	return nil
}

type browserLine struct {
	data []byte
	err  error
}

// The inherited pipes are the only browser control channel. Neither document
// URLs nor encryption keys are emitted in verification requests.
type stdioBrowserBootstrap struct {
	mu     sync.Mutex
	input  io.ReadCloser
	output io.Writer
	lines  chan browserLine
	done   chan struct{}
	closed sync.Once
}

func newStdioBrowserBootstrap(input io.ReadCloser, output io.Writer) *stdioBrowserBootstrap {
	b := &stdioBrowserBootstrap{input: input, output: output, lines: make(chan browserLine, 1), done: make(chan struct{})}
	go b.readLines()
	return b
}

func (b *stdioBrowserBootstrap) Close() {
	b.closed.Do(func() {
		close(b.done)
		_ = b.input.Close()
	})
}

func (b *stdioBrowserBootstrap) readLines() {
	defer close(b.lines)
	scanner := bufio.NewScanner(b.input)
	scanner.Buffer(make([]byte, 4096), browserReplyLimit+2)
	for scanner.Scan() {
		if len(scanner.Bytes()) > browserReplyLimit {
			b.deliver(browserLine{err: errBrowserReply})
			return
		}
		if !b.deliver(browserLine{data: append([]byte(nil), scanner.Bytes()...)}) {
			return
		}
	}
	if scanner.Err() != nil {
		b.deliver(browserLine{err: errBrowserReply})
	}
}

func (b *stdioBrowserBootstrap) deliver(line browserLine) bool {
	select {
	case <-b.done:
		return false
	case b.lines <- line:
		return true
	}
}

func (b *stdioBrowserBootstrap) Cookies(ctx context.Context) (*yandex.BrowserSession, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	select {
	case <-b.done:
		return nil, errBrowserReply
	default:
	}
	var nonce [16]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, errBrowserReply
	}
	id := hex.EncodeToString(nonce[:])
	if _, err := fmt.Fprintln(b.output, "OPENFLUX_BROWSER_VERIFY", id); err != nil {
		return nil, errBrowserReply
	}
	// A late reply to a cancelled request must never satisfy the next request.
	for stale := 0; stale < 16; stale++ {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-b.done:
			return nil, errBrowserReply
		case line, ok := <-b.lines:
			if !ok || line.err != nil {
				return nil, errBrowserReply
			}
			reply, err := parseBrowserReply(line.data)
			if err != nil {
				return nil, errBrowserReply
			}
			if reply.ID != id {
				continue
			}
			if reply.Error != "" {
				return nil, yandex.ErrBrowserSession
			}
			return &yandex.BrowserSession{Cookies: reply.Cookies, UserAgent: string(reply.UserAgent), ClassicDocument: reply.ClassicDocument}, nil
		}
	}
	return nil, errBrowserReply
}

func parseBrowserReply(data []byte) (browserReply, error) {
	var reply browserReply
	if len(data) == 0 || len(data) > browserReplyLimit || !utf8.Valid(data) {
		return reply, errBrowserReply
	}
	// encoding/json normally accepts duplicate fields, making security checks
	// ambiguous across the different platform implementations.
	tokens := json.NewDecoder(bytes.NewReader(data))
	if checkUniqueJSON(tokens, 0) != nil {
		return reply, errBrowserReply
	}
	if _, err := tokens.Token(); err != io.EOF {
		return reply, errBrowserReply
	}
	fields, err := browserObjectFields(data, map[string]byte{
		"id": '"', "cookies": '[', "error": '"', "user_agent": '"', "classic_document": '{',
	})
	if err != nil || fields["id"] == nil {
		return reply, errBrowserReply
	}
	if fields["error"] != nil {
		if len(fields) != 2 {
			return reply, errBrowserReply
		}
	} else {
		var cookies []json.RawMessage
		if fields["cookies"] == nil || json.Unmarshal(fields["cookies"], &cookies) != nil || len(cookies) == 0 || len(cookies) > 64 {
			return reply, errBrowserReply
		}
		for _, raw := range cookies {
			cookie, err := browserObjectFields(raw, map[string]byte{
				"name": '"', "value": '"', "domain": '"', "path": '"', "secure": 'b', "expires": 'n',
			})
			if err != nil || len(cookie) != 6 {
				return reply, errBrowserReply
			}
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&reply) != nil || !validBrowserRequestID(reply.ID) {
		return browserReply{}, errBrowserReply
	}
	if fields["error"] != nil {
		if reply.Error != "verification_failed" {
			return browserReply{}, errBrowserReply
		}
	} else if len(reply.Cookies) == 0 || len(reply.Cookies) > 64 {
		return browserReply{}, errBrowserReply
	}
	return reply, nil
}

func browserObjectFields(data []byte, types map[string]byte) (map[string]json.RawMessage, error) {
	data = bytes.TrimSpace(data)
	if len(data) == 0 || data[0] != '{' {
		return nil, errBrowserReply
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(data, &fields) != nil {
		return nil, errBrowserReply
	}
	for name, raw := range fields {
		kind, ok := types[name]
		if !ok || len(raw) == 0 {
			return nil, errBrowserReply
		}
		switch kind {
		case 'b':
			if string(raw) != "true" && string(raw) != "false" {
				return nil, errBrowserReply
			}
		case 'n':
			if raw[0] != '-' && (raw[0] < '0' || raw[0] > '9') {
				return nil, errBrowserReply
			}
		default:
			if raw[0] != kind {
				return nil, errBrowserReply
			}
		}
	}
	return fields, nil
}

func validBrowserRequestID(id string) bool {
	if len(id) != 32 {
		return false
	}
	for _, value := range id {
		if !(value >= '0' && value <= '9') && !(value >= 'a' && value <= 'f') {
			return false
		}
	}
	return true
}

func checkUniqueJSON(decoder *json.Decoder, depth int) error {
	if depth > 8 {
		return errBrowserReply
	}
	token, err := decoder.Token()
	if err != nil {
		return errBrowserReply
	}
	delim, compound := token.(json.Delim)
	if !compound {
		return nil
	}
	switch delim {
	case '{':
		seen := make(map[string]bool)
		for decoder.More() {
			key, err := decoder.Token()
			if err != nil {
				return errBrowserReply
			}
			name, ok := key.(string)
			if !ok || seen[name] {
				return errBrowserReply
			}
			seen[name] = true
			if checkUniqueJSON(decoder, depth+1) != nil {
				return errBrowserReply
			}
		}
	case '[':
		for decoder.More() {
			if checkUniqueJSON(decoder, depth+1) != nil {
				return errBrowserReply
			}
		}
	default:
		return errBrowserReply
	}
	closing, err := decoder.Token()
	if err != nil || (delim == '{' && closing != json.Delim('}')) || (delim == '[' && closing != json.Delim(']')) {
		return errBrowserReply
	}
	return nil
}
