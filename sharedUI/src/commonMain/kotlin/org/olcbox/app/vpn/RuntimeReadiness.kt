package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.olcbox.app.data.model.LocationConfig
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/** Short native waits let a cancelled connection release the startup lock promptly. */
internal suspend fun awaitRuntimeReady(
    timeoutMillis: Long,
    timeSource: TimeSource = TimeSource.Monotonic,
    waitReady: (Long) -> Unit
) {
    val started = timeSource.markNow()
    while (true) {
        coroutineContext.ensureActive()
        val remaining = timeoutMillis - started.elapsedNow().inWholeMilliseconds
        if (remaining <= 0) error(RUNTIME_READY_TIMEOUT_MESSAGE)
        try {
            waitReady(minOf(remaining, 200L))
            coroutineContext.ensureActive()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            coroutineContext.ensureActive()
            if (failure.message != RUNTIME_READY_TIMEOUT_MESSAGE) throw failure
        }
    }
}

internal fun jitsiRestartSettleDelayMillis(
    provider: String,
    room: String,
    lastStoppedRoom: String?,
    elapsedSinceStopMillis: Long,
    settleMillis: Long
): Long {
    if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI ||
        room.isBlank() || room != lastStoppedRoom
    ) return 0L
    return (settleMillis - elapsedSinceStopMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
}

private const val RUNTIME_READY_TIMEOUT_MESSAGE = "olcRTC runtime readiness timed out"
