package org.olcbox.app.data.logging

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DiagnosticLogSanitizerTest {
    @Test
    fun redactsCredentialsAndConnectionLinks() {
        val source = """
            VLESS vless://client-id@example.org:443?token=query-secret
            {"password":"ssh-secret","private_key":"wireguard-secret","uuid":"client-uuid"}
            Proxy socks5://alice:proxy-secret@127.0.0.1:1080
            PrivateKey = ini-secret
            Bundle unifiedvpn-friend-v1:encrypted-secret
        """.trimIndent()

        val sanitized = sanitizeDiagnosticLogLine(source)

        listOf(
            "client-id",
            "query-secret",
            "ssh-secret",
            "wireguard-secret",
            "client-uuid",
            "proxy-secret",
            "ini-secret",
            "encrypted-secret"
        ).forEach { secret ->
            assertFalse(secret in sanitized, "Secret was not redacted: $secret")
        }
        assertContains(sanitized, "vless://<redacted>")
        assertContains(sanitized, "PrivateKey = <redacted>")
    }

    @Test
    fun keepsUsefulNonSecretDiagnostics() {
        val source = "jitsi join failed for https://meet.example.org/room-name: host-unknown"

        val sanitized = sanitizeDiagnosticLogLine(source)

        assertContains(sanitized, "https://meet.example.org/room-name")
        assertContains(sanitized, "host-unknown")
    }
}
