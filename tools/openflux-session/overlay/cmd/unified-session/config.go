// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
)

const versionText = "unified-openflux-session 1 upstream=7336e1b9d940fc09d00bbf545d15b9437487aa10 protocol=upstream-openflux-session"

type configuration struct {
	Version                 int    `json:"version"`
	Mode                    string `json:"mode"`
	Transport               string `json:"transport"`
	WireCodec               string `json:"wire_codec"`
	DocumentURL             string `json:"document_url"`
	EncryptionKey           string `json:"encryption_key"`
	SOCKS5                  string `json:"socks5"`
	SOCKSUsername           string `json:"socks_username"`
	SOCKSPassword           string `json:"socks_password"`
	DNSServer               string `json:"dns_server"`
	HandshakeTimeoutSeconds int    `json:"handshake_timeout_seconds"`
}

var secretPattern = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)
var telemostPath = regexp.MustCompile(`^/j/[0-9]{14}$`)
var mailruPath = regexp.MustCompile(`^/public/[A-Za-z0-9]+/[A-Za-z0-9]+$`)

func readConfiguration(path string) (configuration, error) {
	file, err := os.Open(path)
	if err != nil {
		return configuration{}, errors.New("private Session configuration unavailable")
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 32769))
	if err != nil || len(data) > 32768 {
		return configuration{}, errors.New("invalid Session configuration size")
	}
	var c configuration
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if d.Decode(&c) != nil {
		return c, errors.New("invalid Session configuration")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return c, errors.New("invalid Session configuration")
	}
	return c, c.validate()
}

func (c configuration) validate() error {
	invalid := errors.New("unsupported or insecure Session configuration")
	if c.Version != 2 || c.Mode != "client" || c.WireCodec != "upstream-session-v1" || !secretPattern.MatchString(c.EncryptionKey) {
		return invalid
	}
	u, err := url.Parse(c.DocumentURL)
	if err != nil || len(c.DocumentURL) > 8192 || u.Scheme != "https" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.ForceQuery || (u.Port() != "" && u.Port() != "443") {
		return invalid
	}
	if strings.ContainsAny(c.DocumentURL, "\r\n\t #;\"") {
		return invalid
	}
	switch c.Transport {
	case "telemost":
		if u.Hostname() != "telemost.yandex.ru" || !telemostPath.MatchString(u.EscapedPath()) {
			return invalid
		}
	case "mailru":
		if u.Hostname() != "cloud.mail.ru" || !mailruPath.MatchString(u.EscapedPath()) {
			return invalid
		}
	default:
		return invalid
	}
	host, portText, err := net.SplitHostPort(c.SOCKS5)
	port, portErr := strconv.Atoi(portText)
	if err != nil || portErr != nil || host != "127.0.0.1" || port < 1 || port > 65535 {
		return invalid
	}
	if (c.SOCKSUsername == "") != (c.SOCKSPassword == "") || len(c.SOCKSUsername) > 255 || len(c.SOCKSPassword) > 255 {
		return invalid
	}
	if c.DNSServer != "1.1.1.1:53" || c.HandshakeTimeoutSeconds != 60 {
		return invalid
	}
	return nil
}
