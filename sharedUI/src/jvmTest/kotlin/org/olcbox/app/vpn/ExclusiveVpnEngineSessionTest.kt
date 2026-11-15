package org.olcbox.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ExclusiveVpnEngineSessionTest {
    @Test fun sameKeyCannotStartTwiceAndAnOldLeaseCannotReleaseItsReplacement() {
        val owners = VpnEngineSessionOwners()
        val first = owners.acquire("synthetic-client-key")
        assertFailsWith<VpnPingUnavailableException> { owners.acquire("synthetic-client-key") }
        first.close()
        val replacement = owners.acquire("synthetic-client-key")
        first.close()
        assertFailsWith<VpnPingUnavailableException> { owners.acquire("synthetic-client-key") }
        owners.acquire("another-client-key").close()
        replacement.close()
    }

    @Test fun failedStartCleansUpAndDoesNotLeaveAStaleLease() = runBlocking {
        val owners = VpnEngineSessionOwners()
        val session = ExclusiveVpnEngineSession({ "synthetic-client-key" }, owners)
        var stops = 0
        assertFailsWith<IllegalStateException> {
            session.start({ error("Synthetic startup failure") }, { stops++ })
        }
        assertEquals(1, stops)
        owners.acquire("synthetic-client-key").close()
        session.start({}, { stops++ })
        session.stop { stops++ }
        session.stop { stops++ }
        assertEquals(2, stops)
    }

    @Test fun stopDuringStartupKeepsLeaseUntilLateProcessesAreCleanedUp() = runBlocking {
        val owners = VpnEngineSessionOwners()
        val session = ExclusiveVpnEngineSession({ "synthetic-client-key" }, owners)
        val entered = CompletableDeferred<Unit>()
        val continueStartup = CompletableDeferred<Unit>()
        var stops = 0
        val startup = launch {
            session.start({ entered.complete(Unit); continueStartup.await() }, { stops++ })
        }
        entered.await()
        session.stop { stops++ }
        assertFailsWith<VpnPingUnavailableException> { owners.acquire("synthetic-client-key") }
        continueStartup.complete(Unit)
        startup.join()
        assertEquals(2, stops)
        owners.acquire("synthetic-client-key").close()
    }

    @Test fun cleanupFailureRetainsOwnershipUntilCleanupRetryAndKeepsTheStartupCause() = runBlocking {
        val owners = VpnEngineSessionOwners()
        val session = ExclusiveVpnEngineSession({ "synthetic-client-key" }, owners)
        val failure = assertFailsWith<IllegalArgumentException> {
            session.start({ throw IllegalArgumentException("Synthetic startup failure") }, { error("Synthetic cleanup failure") })
        }
        assertEquals("Synthetic startup failure", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertFailsWith<VpnPingUnavailableException> { owners.acquire("synthetic-client-key") }
        session.stop {}
        owners.acquire("synthetic-client-key").close()
    }

    @Test fun startupUnwindCannotReleaseLeaseWhileAnotherStopStillCleansUp() = runBlocking {
        val owners = VpnEngineSessionOwners()
        val session = ExclusiveVpnEngineSession({ "synthetic-client-key" }, owners)
        val entered = CompletableDeferred<Unit>()
        val finishStartup = CompletableDeferred<Unit>()
        val startupUnwinding = CountDownLatch(1)
        val firstCleanupEntered = CountDownLatch(1)
        val finishFirstCleanup = CountDownLatch(1)
        val secondCleanupEntered = CountDownLatch(1)
        val startup = async(Dispatchers.Default) {
            runCatching { session.start({ entered.complete(Unit); finishStartup.await(); startupUnwinding.countDown(); error("Synthetic failure") },
                { secondCleanupEntered.countDown() }) }
        }
        entered.await()
        val stopper = Thread {
            session.stop { firstCleanupEntered.countDown(); check(finishFirstCleanup.await(5, TimeUnit.SECONDS)) }
        }.apply { start() }
        try {
            assertTrue(firstCleanupEntered.await(5, TimeUnit.SECONDS))
            finishStartup.complete(Unit)
            assertTrue(startupUnwinding.await(5, TimeUnit.SECONDS))
            assertFalse(secondCleanupEntered.await(100, TimeUnit.MILLISECONDS))
            assertFalse(startup.isCompleted)
            assertFailsWith<VpnPingUnavailableException> { owners.acquire("synthetic-client-key") }
        } finally { finishFirstCleanup.countDown() }
        stopper.join(5_000)
        assertFalse(stopper.isAlive)
        assertIs<IllegalStateException>(startup.await().exceptionOrNull())
        assertEquals(0L, secondCleanupEntered.count)
        owners.acquire("synthetic-client-key").close()
    }
}
