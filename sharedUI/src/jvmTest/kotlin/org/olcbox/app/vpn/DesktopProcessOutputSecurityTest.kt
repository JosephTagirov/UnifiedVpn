package org.olcbox.app.vpn

import org.olcbox.app.data.logging.sanitizeDiagnosticLogLine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopProcessOutputSecurityTest {
    @Test
    fun engineOutputIsSanitizedBeforeEverySink() {
        val stored = mutableListOf<String>()
        val console = mutableListOf<String>()

        emitSanitizedDesktopProcessOutput(
            source = "sing-box",
            line = "failed vless://client-id@example.org:443?token=query-secret password=plain-secret",
            logSink = stored::add,
            consoleSink = console::add
        )

        assertEquals(stored, console)
        assertEquals(1, console.size)
        assertContains(console.single(), "vless://<redacted>")
        assertEquals(console.single(), sanitizeDiagnosticLogLine(console.single()))
        listOf("client-id", "query-secret", "plain-secret").forEach { secret ->
            assertFalse(secret in console.single(), "Secret reached a process-output sink: $secret")
        }
    }
}
