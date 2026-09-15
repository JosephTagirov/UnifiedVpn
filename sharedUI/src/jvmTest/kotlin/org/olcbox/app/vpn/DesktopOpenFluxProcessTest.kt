package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopOpenFluxProcessTest {
    @Test
    fun requiresTheExactAuthenticatedReadinessMarker() {
        assertTrue(isDesktopOpenFluxReady("OPENFLUX_READY"))
        assertTrue(isDesktopOpenFluxReady("OPENFLUX_READY\r"))
        listOf(
            "SOCKS5 server listening",
            "waiting for OPENFLUX_READY",
            "OPENFLUX_READY failed",
            "openflux_ready",
            ""
        ).forEach { line -> assertFalse(isDesktopOpenFluxReady(line)) }

        assertFalse(
            isDesktopEngineReady(
                readinessSignalReceived = false,
                portAcceptsConnections = true,
                requireReadinessSignal = true
            )
        )
    }

    @Test
    fun startupFailuresAreSanitizedBeforeBecomingStatusErrors() {
        val error = assertNotNull(
            desktopNativeStartupFailure("FATAL failed to start: password=startup-secret token=private-token")
        )

        assertContains(error, "FATAL failed to start")
        assertFalse("startup-secret" in error)
        assertFalse("private-token" in error)
        assertEquals(
            "OPENFLUX_FATAL: authenticated handshake failed",
            desktopNativeStartupFailure("OPENFLUX_FATAL: authenticated handshake failed")
        )
        assertNull(desktopNativeStartupFailure("OPENFLUX_READY"))
        assertNull(desktopNativeStartupFailure("waiting for authenticated handshake"))
    }

    @Test
    fun nativeOpenFluxLogsAreSanitizedBeforeEverySink() {
        val logs = mutableListOf<String>()
        val console = mutableListOf<String>()
        val encryptionKey = "ab".repeat(32)
        emitSanitizedDesktopProcessOutput(
            source = "OpenFlux",
            line = "authentication failed: password=proxy-secret token=document-token " +
                "encryption_key=$encryptionKey document_url=https://docs.yandex.ru/docs/private-document",
            logSink = logs::add,
            consoleSink = console::add
        )

        assertEquals(logs, console)
        assertEquals(1, logs.size)
        assertFalse("proxy-secret" in console.single())
        assertFalse("document-token" in console.single())
        assertFalse(encryptionKey in console.single())
        assertFalse("private-document" in console.single())
    }
}
