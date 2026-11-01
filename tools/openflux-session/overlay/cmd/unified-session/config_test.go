// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"strings"
	"testing"
)

func validConfiguration() configuration {
	return configuration{Version: 2, Mode: "client", Transport: "telemost", WireCodec: "upstream-session-v1",
		DocumentURL: "https://telemost.yandex.ru/j/12345678901234", EncryptionKey: strings.Repeat("Ab12", 16),
		SOCKS5: "127.0.0.1:18951", SOCKSUsername: "test", SOCKSPassword: "private-test", DNSServer: "1.1.1.1:53", HandshakeTimeoutSeconds: 60}
}

func TestConfigurationSafety(t *testing.T) {
	c := validConfiguration()
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	for _, mutate := range []func(*configuration){
		func(c *configuration) { c.Version = 1 }, func(c *configuration) { c.Mode = "server" },
		func(c *configuration) { c.WireCodec = "legacy-lz4-v1" }, func(c *configuration) { c.EncryptionKey = "" },
		func(c *configuration) { c.Transport = "direct" }, func(c *configuration) { c.SOCKS5 = "0.0.0.0:1080" },
		func(c *configuration) { c.SOCKSPassword = "" }, func(c *configuration) { c.DNSServer = "localhost:53" },
		func(c *configuration) { c.DocumentURL = "https://evil.invalid/j/12345678901234" },
		func(c *configuration) { c.DocumentURL += "?x=1" }, func(c *configuration) { c.DocumentURL += "\n" },
	} {
		bad := c
		mutate(&bad)
		if bad.validate() == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}

func TestDirectEncryptedConfig(t *testing.T) {
    c := validConfiguration()
    c.Transport = "direct"
    c.DocumentURL = "tcp://151.243.209.234:50000"
    if err := c.validate(); err != nil { t.Fatal(err) }
    for _, endpoint := range []string{"tcp://127.0.0.1:50000", "tcp://10.0.0.1:50000", "tcp://151.243.209.234:22", "tcp://151.243.209.234:50000/path", "https://151.243.209.234:50000"} {
        c.DocumentURL = endpoint
        if c.validate() == nil { t.Fatal("unsafe direct endpoint accepted") }
    }
}

func TestStdinStopAndEof(t *testing.T) {
	for _, input := range []string{"STOP\n", "STOP\r\n", "", strings.Repeat("x", 32)} {
		ctx, cancel := context.WithCancel(context.Background())
		stopOnInput(strings.NewReader(input), cancel)
		if ctx.Err() == nil {
			t.Fatal("control pipe failed to cancel")
		}
	}
}
