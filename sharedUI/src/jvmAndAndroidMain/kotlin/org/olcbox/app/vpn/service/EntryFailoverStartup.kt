package org.olcbox.app.vpn.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Local DIRECT remains usable while the independently owned monitor verifies VPN entries. */
internal suspend fun startEntryFailoverSession(
    directRoutingEnabled: Boolean,
    startupTimeoutMillis: Long,
    startLocalFront: suspend () -> Unit,
    startHealthMonitor: (CompletableDeferred<Unit>) -> Unit
) {
    currentCoroutineContext().ensureActive()
    startLocalFront()
    currentCoroutineContext().ensureActive()
    val verifiedRoute = CompletableDeferred<Unit>()
    startHealthMonitor(verifiedRoute)
    // The front starts with its VPN balancer blocked. Only the monitor's acknowledged
    // override can open that route; DIRECT needs just the local listener and TUN.
    if (!directRoutingEnabled) withTimeout(startupTimeoutMillis) { verifiedRoute.await() }
    currentCoroutineContext().ensureActive()
}
