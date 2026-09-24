package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.model.LocationConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTime::class)
class RuntimeReadinessTest {
    @Test
    fun cancellingAStuckStartDoesNotWaitForTheConnectionTimeout() = runBlocking {
        val entered = CountDownLatch(1)
        val oversizedPoll = AtomicBoolean(false)
        val connection = launch(Dispatchers.IO) {
            awaitRuntimeReady(60_000) { timeout ->
                if (timeout !in 1L..200L) oversizedPoll.set(true)
                entered.countDown()
                Thread.sleep(timeout)
                error(READY_TIMEOUT)
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { connection.cancelAndJoin() }
            assertFalse(oversizedPoll.get())
        } finally {
            connection.cancelAndJoin()
        }
    }

    @Test
    fun onlyReadinessTimeoutIsRetried() = runBlocking {
        val clock = TestTimeSource()
        val polls = mutableListOf<Long>()
        awaitRuntimeReady(60_000, clock) { timeout ->
            polls += timeout
            clock += timeout.milliseconds
            if (polls.size < 3) error(READY_TIMEOUT)
        }
        assertEquals(listOf(200L, 200L, 200L), polls)
    }

    @Test
    fun totalDeadlineIsNotResetForEachPoll() = runBlocking {
        val clock = TestTimeSource()
        val polls = mutableListOf<Long>()
        val failure = assertFailsWith<IllegalStateException> {
            awaitRuntimeReady(450, clock) { timeout ->
                polls += timeout
                clock += timeout.milliseconds
                error(READY_TIMEOUT)
            }
        }
        assertEquals(READY_TIMEOUT, failure.message)
        assertEquals(listOf(200L, 200L, 50L), polls)
    }

    @Test
    fun slowConnectionCanBecomeReadyAfterTheOldTwentyFiveSecondLimit() = runBlocking {
        val clock = TestTimeSource()
        var polls = 0
        awaitRuntimeReady(60_000, clock) { timeout ->
            clock += timeout.milliseconds
            if (++polls < 151) error(READY_TIMEOUT)
        }
        assertEquals(151, polls)
    }

    @Test
    fun fatalCoreFailureIsNotRetried() = runBlocking {
        val failure = IllegalStateException("handshake failed")
        var calls = 0
        val actual = assertFailsWith<IllegalStateException> {
            awaitRuntimeReady(60_000) {
                calls++
                throw failure
            }
        }
        assertSame(failure, actual)
        assertEquals(1, calls)
    }

    @Test
    fun cancelledOrSupersededCallbackIsNotRetried() = runBlocking {
        val cancelled = CancellationException(READY_TIMEOUT)
        var calls = 0
        val actual = assertFailsWith<CancellationException> {
            awaitRuntimeReady(60_000) {
                calls++
                throw cancelled
            }
        }
        assertSame(cancelled, actual)
        assertEquals(1, calls)
    }

    @Test
    fun expiredDeadlineNeverCallsNativeWaitWithZeroTimeout() = runBlocking {
        var calls = 0
        val failure = assertFailsWith<IllegalStateException> {
            awaitRuntimeReady(0) { calls++ }
        }
        assertEquals(READY_TIMEOUT, failure.message)
        assertEquals(0, calls)
    }

    @Test
    fun onlyTheSameJitsiRoomWaitsForCleanup() {
        assertEquals(1_750L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, ROOM_A, 250))
        assertEquals(0L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_B, ROOM_A, 250))
        assertEquals(0L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, null, 250))
        assertEquals(0L, settleDelay(LocationConfig.PROVIDER_JITSI, "", "", 250))
        assertEquals(0L, settleDelay(LocationConfig.PROVIDER_WB_STREAM, ROOM_A, ROOM_A, 250))
    }

    @Test
    fun cleanupDelayExpiresAndNeverExceedsTheSettleWindow() {
        assertEquals(2_000L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, ROOM_A, 0))
        assertEquals(1L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, ROOM_A, 1_999))
        assertEquals(0L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, ROOM_A, 2_000))
        assertEquals(0L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, ROOM_A, 20_000))
        assertEquals(2_000L, settleDelay(LocationConfig.PROVIDER_JITSI, ROOM_A, ROOM_A, -10))
    }

    private fun settleDelay(provider: String, room: String, lastRoom: String?, elapsed: Long): Long =
        jitsiRestartSettleDelayMillis(provider, room, lastRoom, elapsed, 2_000L)

    private companion object {
        const val READY_TIMEOUT = "olcRTC runtime readiness timed out"
        const val ROOM_A = "https://meet.example.com/test-a"
        const val ROOM_B = "https://meet.example.com/test-b"
    }
}
