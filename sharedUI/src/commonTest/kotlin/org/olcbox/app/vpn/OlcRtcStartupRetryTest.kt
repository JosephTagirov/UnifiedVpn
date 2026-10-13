package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OlcRtcStartupRetryTest {
    @Test
    fun transientHandshakeFailureRetriesThenReturnsReadyResult() = runTest {
        var attempts = 0
        val retries = mutableListOf<Int>()
        val result = retryOlcRtcStartup({ true }, { retry, _ -> retries += retry }) {
            attempts++
            if (attempts < 3) error("client: handshake: read welcome: timeout")
            "ready"
        }
        assertEquals("ready", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), retries)
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun retriesAreBounded() = runTest {
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            retryOlcRtcStartup({ true }, { _, _ -> }) {
                attempts++
                error("olcRTC start timed out")
            }
        }
        assertEquals(4, attempts)
        assertEquals(14_000L, currentTime)
    }

    @Test
    fun translatedHandshakeTimeoutRemainsRetryable() = runTest {
        var attempts = 0
        retryOlcRtcStartup({ true }, { _, _ -> }) {
            if (attempts++ == 0) error(friendlyOlcRtcFailure("client: handshake: timeout"))
        }
        assertEquals(2, attempts)
    }

    @Test
    fun invalidKeysProtocolsAndCertificatesFailWithoutRetries() = runTest {
        for (message in listOf(
            "invalid key", "bad record magic", "authentication failed", "permission denied",
            "websocket dial timeout: x509: certificate signed by unknown authority"
        )) {
            var attempts = 0
            var retries = 0
            assertFailsWith<IllegalStateException> {
                retryOlcRtcStartup({ true }, { _, _ -> retries++ }) {
                    attempts++
                    error(message)
                }
            }
            assertEquals(1, attempts)
            assertEquals(0, retries)
        }
        assertEquals(0L, currentTime)
    }

    @Test
    fun supersedingStartDuringBackoffDoesNotLaunchAnotherCore() = runTest {
        var current = true
        var attempts = 0
        val operation = launch {
            retryOlcRtcStartup({ current }, { _, _ -> }) {
                attempts++
                error("handshake timeout")
            }
        }
        advanceTimeBy(50)
        current = false
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(1, attempts)
        assertTrue(currentTime <= 200)
    }

    @Test
    fun explicitCancellationIsNeverRetried() = runTest {
        var attempts = 0
        assertFailsWith<CancellationException> {
            retryOlcRtcStartup({ true }, { _, _ -> error("Unexpected retry") }) {
                attempts++
                throw CancellationException("Stopped")
            }
        }
        assertEquals(1, attempts)
        assertEquals(0L, currentTime)
    }

    @Test
    fun alreadySupersededStartDoesNotLaunchAnything() = runTest {
        assertFailsWith<CancellationException> {
            retryOlcRtcStartup({ false }, { _, _ -> error("Unexpected retry") }) {
                error("Unexpected launch")
            }
        }
    }
}
