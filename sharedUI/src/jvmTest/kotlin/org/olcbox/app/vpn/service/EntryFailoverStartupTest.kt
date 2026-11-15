package org.olcbox.app.vpn.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryFailoverStartupTest {
    @Test fun directStartsOnlyAfterLocalReadinessWhileBothEntriesRemainUnavailable() = runBlocking {
        val localReady = CompletableDeferred<Unit>()
        var localStarts = 0
        var verifiedRoute: CompletableDeferred<Unit>? = null
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            startEntryFailoverSession(true, 60_000, {
                localStarts++
                localReady.await()
            }, { verifiedRoute = it })
        }
        assertFalse(startup.isCompleted)
        assertEquals(null, verifiedRoute, "Remote work must not precede the local DIRECT listener")
        localReady.complete(Unit)
        withTimeout(1_000) { startup.await() }
        val route = requireNotNull(verifiedRoute)
        assertFalse(route.isCompleted, "No successful VPN entry is required for DIRECT readiness")

        // A later successful probe/API readback releases VPN without restarting the local front.
        route.complete(Unit)
        assertEquals(1, localStarts)
        assertTrue(startup.isCompleted)
    }

    @Test fun profilesWithoutDirectStillWaitForTheVerifiedRoute() = runBlocking {
        var verifiedRoute: CompletableDeferred<Unit>? = null
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            startEntryFailoverSession(false, 60_000, {}, { verifiedRoute = it })
        }
        assertFalse(startup.isCompleted)
        requireNotNull(verifiedRoute).complete(Unit)
        withTimeout(1_000) { startup.await() }
    }

    @Test fun unavailableEntriesStillTimeOutWhenNoDirectRouteExists() = runBlocking {
        assertFailsWith<TimeoutCancellationException> {
            startEntryFailoverSession(false, 25, {}, {})
        }
        Unit
    }

    @Test fun localFailureDoesNotStartTheHealthMonitor() = runBlocking {
        var monitorStarted = false
        assertFailsWith<IllegalStateException> {
            startEntryFailoverSession(true, 60_000, { error("Local front failed") }, { monitorStarted = true })
        }
        assertFalse(monitorStarted)
    }

    @Test fun cancellationWhileWaitingForLocalReadinessDoesNotStartRemoteWork() = runBlocking {
        val localReady = CompletableDeferred<Unit>()
        var monitorStarted = false
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            startEntryFailoverSession(true, 60_000, { localReady.await() }, { monitorStarted = true })
        }
        startup.cancel()
        assertFailsWith<CancellationException> { startup.await() }
        assertFalse(monitorStarted)
    }
}
