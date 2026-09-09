package org.olcbox.app.vpn

internal class OlcRtcStartRetryPolicy(private val maxRetries: Int) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }

    fun shouldRetry(message: String, retriesScheduled: Int): Boolean {
        require(retriesScheduled >= 0) { "retriesScheduled must not be negative" }
        return retriesScheduled < maxRetries && isRetryableOlcRtcStartFailure(message)
    }

    fun retryDelayMillis(
        retriesScheduled: Int,
        baseDelayMillis: Long,
        maxDelayMillis: Long
    ): Long {
        require(retriesScheduled >= 0) { "retriesScheduled must not be negative" }
        require(baseDelayMillis > 0L) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) {
            "maxDelayMillis must not be smaller than baseDelayMillis"
        }

        var delayMillis = baseDelayMillis
        repeat(retriesScheduled) {
            delayMillis = if (delayMillis >= maxDelayMillis - delayMillis) {
                maxDelayMillis
            } else {
                delayMillis * 2L
            }
        }
        return delayMillis.coerceAtMost(maxDelayMillis)
    }
}

internal fun isRetryableOlcRtcStartFailure(message: String): Boolean {
    val text = message.lowercase()

    if (NON_RETRYABLE_OLCRTC_FAILURES.any(text::contains)) return false

    return RETRYABLE_OLCRTC_FAILURES.any(text::contains) ||
        ("handshake" in text && ("timeout" in text || "timed out" in text)) ||
        ("dial" in text && ("timeout" in text || "timed out" in text)) ||
        ("websocket" in text && ("closed" in text || "timeout" in text))
}

private val NON_RETRYABLE_OLCRTC_FAILURES = listOf(
    "bad record magic",
    "incompatible record-layer",
    "invalid mobile runtime configuration",
    "validate config",
    "invalid config",
    "invalid key",
    "64 hexadecimal",
    "unsupported provider",
    "unsupported transport",
    "host-unknown",
    "authentication failed",
    "unauthorized",
    "forbidden",
    "permission denied",
    "malformed"
)

private val RETRYABLE_OLCRTC_FAILURES = listOf(
    "runtime readiness timed out",
    "runtime stopped before becoming ready",
    "runtime is already active",
    "runtime is still stopping",
    "use of closed network connection",
    "network is unreachable",
    "unreachable network",
    "no route to host",
    "no such host",
    "connection reset",
    "connection refused",
    "connection aborted",
    "temporarily unavailable",
    "temporary failure",
    "i/o timeout",
    "tls handshake timeout",
    "broken pipe",
    "unexpected eof",
    "ice connection failed",
    "peer connection failed"
)
