package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal suspend fun <T> retryOlcRtcStartup(
    isCurrent: () -> Boolean,
    onRetry: (retry: Int, delayMillis: Long) -> Unit,
    attempt: suspend () -> T
): T {
    val policy = OlcRtcStartRetryPolicy(maxRetries = 3)
    var retries = 0
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Desktop start superseded")
    }

    while (true) {
        checkCurrent()
        try {
            return attempt()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            checkCurrent()
            if (!policy.shouldRetry(error.message.orEmpty(), retries)) throw error
            val wait = policy.retryDelayMillis(retries, baseDelayMillis = 2_000, maxDelayMillis = 8_000)
            retries++
            onRetry(retries, wait)
            var remaining = wait
            while (remaining > 0) {
                checkCurrent()
                val interval = minOf(remaining, 200L)
                delay(interval)
                remaining -= interval
            }
        }
    }
}
