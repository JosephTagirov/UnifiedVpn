// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func validClientConfig() configuration {
	return configuration{Version: 1, Mode: "client", Transport: "yandex",
		DocumentURL:   "https://docs.yandex.ru/docs/view?url=test-fixture",
		EncryptionKey: strings.Repeat("ab", 32), SOCKS5: "127.0.0.1:19808"}
}

func TestReadConfigReturnsNormalizedDefaults(t *testing.T) {
	c := validClientConfig()
	c.DocumentURL = "  https://DOCS.YANDEX.RU/docs/view?url=test-fixture  "
	c.EncryptionKey = " " + strings.Repeat("AB", 32) + " "
	data, _ := json.Marshal(c)
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	actual, err := readConfiguration(path)
	if err != nil {
		t.Fatal(err)
	}
	if actual.HandshakeTimeoutSeconds != 60 || actual.DNSServer != "1.1.1.1:53" ||
		actual.EncryptionKey != strings.Repeat("ab", 32) || actual.DocumentURL != strings.TrimSpace(c.DocumentURL) {
		t.Fatal("configuration defaults or normalization were not returned")
	}
}

func TestConfigRejectsPlaintextAndUnsafeOptions(t *testing.T) {
	cases := []struct {
		name   string
		change func(*configuration)
	}{
		{"missing key", func(c *configuration) { c.EncryptionKey = "" }},
		{"short key", func(c *configuration) { c.EncryptionKey = strings.Repeat("a", 63) }},
		{"nonhex key", func(c *configuration) { c.EncryptionKey = strings.Repeat("z", 64) }},
		{"plaintext URL", func(c *configuration) { c.DocumentURL = "http://docs.yandex.ru/docs/test" }},
		{"foreign host", func(c *configuration) { c.DocumentURL = "https://docs.yandex.ru.example.org/docs/test" }},
		{"URL user info", func(c *configuration) { c.DocumentURL = "https://secret@docs.yandex.ru/docs/test" }},
		{"URL fragment", func(c *configuration) { c.DocumentURL += "#secret" }},
		{"URL whitespace", func(c *configuration) { c.DocumentURL += "\u00a0test" }},
		{"nonloopback proxy", func(c *configuration) { c.SOCKS5 = "0.0.0.0:19808" }},
		{"unpaired auth", func(c *configuration) { c.SOCKSUsername = "secret" }},
		{"auth bytes", func(c *configuration) { c.SOCKSUsername = strings.Repeat("\u00e9", 128); c.SOCKSPassword = "secret" }},
		{"oversized timeout", func(c *configuration) { c.HandshakeTimeoutSeconds = 181 }},
		{"server client fields", func(c *configuration) { c.Mode = "server" }},
	}
	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			c := validClientConfig()
			item.change(&c)
			err := c.validate()
			if err == nil {
				t.Fatal("unsafe configuration accepted")
			}
			if strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), c.DocumentURL) {
				t.Fatal("sensitive values in error")
			}
		})
	}
}

func TestConfigStrictJSONAndSize(t *testing.T) {
	c := validClientConfig()
	data, _ := json.Marshal(c)
	cases := []string{string(data) + "{}", strings.Repeat(" ", 32769),
		strings.TrimSuffix(string(data), "}") + `,"unknown":"secret"}`, "not-json-secret"}
	for _, content := range cases {
		path := filepath.Join(t.TempDir(), "config.json")
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
		_, err := readConfiguration(path)
		if err == nil {
			t.Fatal("invalid configuration accepted")
		}
		if strings.Contains(err.Error(), "secret") {
			t.Fatal("config content leaked")
		}
	}
}
