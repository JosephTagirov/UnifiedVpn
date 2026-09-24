// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"unicode"
)

const versionText = "unified-openflux 5 upstream=d34dc8caa70ca059cd80d8f5753499361052dabc protocol=unified-openflux-aesgcm-v1"

type configuration struct {
	Version                 int    `json:"version"`
	Mode                    string `json:"mode"`
	Transport               string `json:"transport"`
	DocumentURL             string `json:"document_url"`
	EncryptionKey           string `json:"encryption_key"`
	SOCKS5                  string `json:"socks5,omitempty"`
	SOCKSUsername           string `json:"socks_username,omitempty"`
	SOCKSPassword           string `json:"socks_password,omitempty"`
	DNSServer               string `json:"dns_server,omitempty"`
	HandshakeTimeoutSeconds int    `json:"handshake_timeout_seconds"`
}

func readConfiguration(path string) (configuration, error) {
	var c configuration
	f, err := os.Open(path)
	if err != nil {
		return c, errors.New("cannot open private configuration file")
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > 32768 {
		return c, errors.New("configuration must be a regular file of at most 32768 bytes")
	}
	d := json.NewDecoder(io.LimitReader(f, 32769))
	d.DisallowUnknownFields()
	if d.Decode(&c) != nil {
		return c, errors.New("invalid OpenFlux configuration JSON")
	}
	if d.Decode(new(any)) != io.EOF {
		return c, errors.New("unexpected trailing configuration data")
	}
	if err := c.validate(); err != nil {
		return configuration{}, err
	}
	return c, nil
}

func (c *configuration) validate() error {
	if c.Version != 1 || (c.Transport != "yandex" && c.Transport != "vyandex") || (c.Mode != "client" && c.Mode != "server") {
		return errors.New("unsupported OpenFlux version, mode, or transport")
	}
	c.DocumentURL = strings.TrimSpace(c.DocumentURL)
	u, err := url.Parse(c.DocumentURL)
	if err != nil || len(c.DocumentURL) > 8192 || strings.IndexFunc(c.DocumentURL, unicode.IsSpace) >= 0 ||
		!strings.EqualFold(u.Scheme, "https") || u.User != nil || u.Fragment != "" || len(u.Path) < 2 ||
		(!strings.EqualFold(u.Hostname(), "docs.yandex.ru") && !strings.EqualFold(u.Hostname(), "disk.yandex.ru")) ||
		(u.Port() != "" && u.Port() != "443") {
		return errors.New("a valid HTTPS Yandex Docs document URL is required")
	}
	c.EncryptionKey = strings.ToLower(strings.TrimSpace(c.EncryptionKey))
	key, err := hex.DecodeString(c.EncryptionKey)
	if err != nil || len(key) != 32 || len(c.EncryptionKey) != 64 {
		return errors.New("encryption_key must contain exactly 64 hexadecimal characters; plaintext is disabled")
	}
	if c.HandshakeTimeoutSeconds == 0 {
		c.HandshakeTimeoutSeconds = 60
	}
	if c.HandshakeTimeoutSeconds < 5 || c.HandshakeTimeoutSeconds > 180 {
		return errors.New("handshake_timeout_seconds must be between 5 and 180")
	}
	if c.Mode == "client" {
		host, port, err := net.SplitHostPort(c.SOCKS5)
		p, portErr := strconv.Atoi(port)
		if err != nil || portErr != nil || host != "127.0.0.1" || p < 1 || p > 65535 {
			return errors.New("SOCKS5 must use a valid 127.0.0.1 loopback port")
		}
		if (c.SOCKSUsername == "") != (c.SOCKSPassword == "") || len(c.SOCKSUsername) > 255 || len(c.SOCKSPassword) > 255 {
			return errors.New("invalid SOCKS5 credentials")
		}
		if c.DNSServer == "" {
			c.DNSServer = "1.1.1.1:53"
		}
		host, port, err = net.SplitHostPort(c.DNSServer)
		ip := net.ParseIP(host)
		if err != nil || ip == nil || ip.To4() == nil || ip.IsUnspecified() || port != "53" {
			return errors.New("DNS must be an IPv4 server on port 53; requests use encrypted tunnel TCP")
		}
	} else if c.SOCKS5 != "" || c.SOCKSUsername != "" || c.SOCKSPassword != "" || c.DNSServer != "" {
		return errors.New("client-only options are not allowed in server configuration")
	}
	return nil
}
