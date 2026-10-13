package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopStartupFailureTest {
    @Test
    fun delayedPermanentStderrPreventsGenericRetryAfterExit() = runTest {
        val failure = CompletableDeferred<String>()
        val message = "client: failed to connect link: x509: certificate signed by unknown authority"
        val reader = launch {
            delay(100)
            failure.complete(checkNotNull(desktopOlcRtcStartupFailure(message)))
        }

        val result = awaitDesktopStartupFailureAfterExit(failure, reader) { true }

        assertEquals(message, result)
        assertFalse(isRetryableOlcRtcStartFailure(checkNotNull(result)))
        assertTrue(reader.isCompleted)
        assertEquals(100L, currentTime)
    }

    @Test
    fun completedReaderWithoutDiagnosticDoesNotDelayGenericFailure() = runTest {
        val reader = Job().apply { complete() }

        assertNull(awaitDesktopStartupFailureAfterExit(CompletableDeferred(), reader) { true })
        assertEquals(0L, currentTime)
    }

    @Test
    fun openPipeHasBoundedWaitWithoutCancellingReader() = runTest {
        val reader = Job()
        try {
            assertNull(awaitDesktopStartupFailureAfterExit(CompletableDeferred(), reader) { true })
            assertEquals(1_000L, currentTime)
            assertTrue(reader.isActive)
        } finally {
            reader.cancel()
        }
    }

    @Test
    fun availableFailureDoesNotWaitForOpenPipe() = runTest {
        val reader = Job()
        val failure = CompletableDeferred("permission denied")
        try {
            assertEquals(
                "permission denied",
                awaitDesktopStartupFailureAfterExit(failure, reader) { true }
            )
            assertEquals(0L, currentTime)
            assertTrue(reader.isActive)
        } finally {
            reader.cancel()
        }
    }

    @Test
    fun supersededRequestCancelsWhileWaitingForReader() = runTest {
        val reader = Job()
        var current = true
        try {
            val operation = async {
                awaitDesktopStartupFailureAfterExit(CompletableDeferred(), reader) { current }
            }
            advanceTimeBy(50)
            current = false

            assertFailsWith<CancellationException> { operation.await() }
            assertTrue(currentTime <= 200L)
            assertTrue(reader.isActive)
        } finally {
            reader.cancel()
        }
    }

    @Test
    fun coroutineCancellationIsNotConsumedAsDrainTimeout() = runTest {
        val reader = Job()
        try {
            val operation = async {
                awaitDesktopStartupFailureAfterExit(CompletableDeferred(), reader) { true }
            }
            runCurrent()
            operation.cancel()

            assertFailsWith<CancellationException> { operation.await() }
            assertEquals(0L, currentTime)
            assertTrue(reader.isActive)
        } finally {
            reader.cancel()
        }
    }

    @Test
    fun parentTimeoutIsNotConsumedAsDrainTimeout() = runTest {
        val reader = Job()
        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50L) {
                    awaitDesktopStartupFailureAfterExit(CompletableDeferred(), reader) { true }
                }
            }
            assertEquals(50L, currentTime)
            assertTrue(reader.isActive)
        } finally {
            reader.cancel()
        }
    }
}
