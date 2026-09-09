package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OlcRtcStartRetryPolicyTest {
    @Test
    fun retriesTransientVpnProtectorAndNetworkFailures() {
        assertTrue(isRetryableOlcRtcStartFailure("protect udp4: use of closed network connection"))
        assertTrue(isRetryableOlcRtcStartFailure("dial tcp: i/o timeout"))
        assertTrue(isRetryableOlcRtcStartFailure("A socket operation was attempted to an unreachable network"))
    }

    @Test
    fun retriesBoundedHandshakeAndRuntimeHandoffs() {
        assertTrue(
            isRetryableOlcRtcStartFailure(
                "handshake client: read welcome: handshake: read hdr: timeout"
            )
        )
        assertTrue(isRetryableOlcRtcStartFailure("olcRTC runtime is already active"))
        assertTrue(isRetryableOlcRtcStartFailure("olcRTC runtime readiness timed out"))
    }

    @Test
    fun neverRetriesProtocolOrConfigurationFailures() {
        assertFalse(isRetryableOlcRtcStartFailure("open record: bad record magic"))
        assertFalse(isRetryableOlcRtcStartFailure("validate config: invalid key"))
        assertFalse(isRetryableOlcRtcStartFailure("unsupported transport: unknown-transport"))
        assertFalse(isRetryableOlcRtcStartFailure("xmpp stream error: host-unknown"))
    }

    @Test
    fun doesNotGuessThatUnknownFailuresAreTransient() {
        assertFalse(isRetryableOlcRtcStartFailure("unclassified native failure"))
    }

    @Test
    fun capsTheNumberOfTransientRetries() {
        val policy = OlcRtcStartRetryPolicy(maxRetries = 2)
        val failure = "olcRTC runtime readiness timed out"

        assertTrue(policy.shouldRetry(failure, retriesScheduled = 0))
        assertTrue(policy.shouldRetry(failure, retriesScheduled = 1))
        assertFalse(policy.shouldRetry(failure, retriesScheduled = 2))
        assertFalse(policy.shouldRetry("invalid key", retriesScheduled = 0))
    }

    @Test
    fun appliesExponentialDelayWithAnUpperBound() {
        val policy = OlcRtcStartRetryPolicy(maxRetries = 5)

        assertEquals(2_000L, policy.retryDelayMillis(0, 2_000L, 30_000L))
        assertEquals(4_000L, policy.retryDelayMillis(1, 2_000L, 30_000L))
        assertEquals(8_000L, policy.retryDelayMillis(2, 2_000L, 30_000L))
        assertEquals(16_000L, policy.retryDelayMillis(3, 2_000L, 30_000L))
        assertEquals(30_000L, policy.retryDelayMillis(4, 2_000L, 30_000L))
    }
}
