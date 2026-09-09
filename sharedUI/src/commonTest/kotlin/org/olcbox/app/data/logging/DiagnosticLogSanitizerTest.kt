package org.olcbox.app.data.logging

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiagnosticLogSanitizerTest {
    @Test
    fun redactsCredentialsAndConnectionLinks() {
        val source = """
            VLESS vless://client-id@example.org:443?token=query-secret
            {"password":"ssh-secret","private_key":"wireguard-secret","uuid":"client-uuid","id":"json-client-id"}
            "password":
              "ssh secret with spaces"
            'api_key' = 'api secret with spaces'
            subscription_url = https://subscriptions.example/private-secret?token=value&key=secondary-secret
            Proxy socks5://alice:proxy-secret@127.0.0.1:1080
            PrivateKey = ini-secret
            olcRTC olcrtc://jitsi?datachannel@meet.example/secret-room#secret-key
            Old bundle unifiedvpn-friend-v1:legacy-encrypted-secret
            Bundle unifiedvpn-friend-v2:encrypted-secret
        """.trimIndent()

        val sanitized = sanitizeDiagnosticLogLine(source)

        listOf(
            "client-id",
            "query-secret",
            "ssh-secret",
            "wireguard-secret",
            "client-uuid",
            "json-client-id",
            "ssh secret with spaces",
            "api secret with spaces",
            "private-secret",
            "secondary-secret",
            "proxy-secret",
            "ini-secret",
            "secret-room",
            "secret-key",
            "legacy-encrypted-secret",
            "encrypted-secret"
        ).forEach { secret ->
            assertFalse(secret in sanitized, "Secret was not redacted: $secret")
        }
        assertContains(sanitized, "vless://<redacted>")
        assertContains(sanitized, "PrivateKey = <redacted>")
        assertEquals(sanitized, sanitizeDiagnosticLogLine(sanitized))
    }

    @Test
    fun keepsUsefulDiagnosticsWithoutExposingOlcRtcRoom() {
        val room = "https://meet.example.org/room-name"
        val source = "jitsi join failed for $room: host-unknown"

        val sanitized = sanitizeDiagnosticLogLine(source)

        assertContains(sanitized, "host=meet.example.org")
        assertContains(sanitized, "roomId=")
        assertFalse("room-name" in sanitized)
        assertContains(sanitized, "host-unknown")
        assertEquals(sanitized, sanitizeDiagnosticLogLine(source))
    }

    @Test
    fun redactsNativeOlcRtcRoomVariants() {
        val room = "https://meet.example.org/private-room"
        val source = "jitsi: joining MUC meet.example.org/private-room as desktop-client"

        val sanitized = sanitizeOlcRtcDiagnosticOutput(source, room)

        assertContains(sanitized, "host=meet.example.org")
        assertContains(sanitized, "roomId=")
        assertFalse("private-room" in sanitized)
        assertContains(sanitized, "desktop-client")
        assertEquals(
            diagnosticOlcRtcRoomReference(room),
            diagnosticOlcRtcRoomReference("meet.example.org/private-room")
        )
    }

    @Test
    fun redactsUnterminatedQuotedSecretFailClosed() {
        val sanitized = sanitizeDiagnosticLogLine("request failed: password=\"secret with spaces")

        assertFalse("secret with spaces" in sanitized)
        assertContains(sanitized, "password=\"<redacted>\"")
    }

    @Test
    fun keepsUnrelatedWebPathForUpdateDiagnostics() {
        val source = "Update failed for https://github.com/example/project/releases: timeout"

        assertEquals(source, sanitizeDiagnosticLogLine(source))
    }
}
