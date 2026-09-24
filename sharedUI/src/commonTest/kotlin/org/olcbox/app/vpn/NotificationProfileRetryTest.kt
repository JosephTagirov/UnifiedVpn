package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationProfileRetryTest {
    @Test
    fun sameFailedActiveProfileCanBeRetried() {
        assertTrue(canRetry(VpnStatus.Error("Synthetic failure"), target = "failed", active = "failed"))
    }

    @Test
    fun failedTargetCanBeRetriedAfterPreviousProfileWasRestored() {
        assertTrue(canRetry(VpnStatus.Connected, target = "failed", active = "previous"))
        assertFalse(canRetry(VpnStatus.Connected, target = "previous", active = "previous"))
    }

    @Test
    fun failedRollbackMakesLatestFailedConnectionRetryable() {
        assertTrue(canRetry(VpnStatus.Error("Rollback failed"), target = "previous", active = "previous", failed = "previous"))
        assertFalse(canRetry(VpnStatus.Error("Rollback failed"), target = "failed", active = "previous", failed = "previous"))
    }

    @Test
    fun busyAndStoppedConnectionsRejectRetry() {
        listOf(VpnStatus.Connecting, VpnStatus.Reconnecting, VpnStatus.Stopping, VpnStatus.Disconnected)
            .forEach { assertFalse(canRetry(it)) }
    }

    @Test
    fun queuedRequestBlocksRapidSecondClick() {
        assertFalse(canRetry(VpnStatus.Error("Synthetic failure"), pending = true))
    }

    @Test
    fun failedCleanupCannotBeRestartedWhileLifecycleIsStopping() {
        assertFalse(canRetry(VpnStatus.Error("Cleanup failed"), stopping = true))
    }

    @Test
    fun missingForegroundAndUnknownTargetsCannotStartConnection() {
        assertFalse(canRetry(VpnStatus.Error("Synthetic failure"), foreground = false))
        assertFalse(canRetry(VpnStatus.Error("Synthetic failure"), target = "unknown"))
        assertFalse(canRetry(VpnStatus.Error("Synthetic failure"), target = null))
    }

    @Test
    fun oldErrorDoesNotCompleteRetryBeforeServiceAcceptsIt() {
        assertNull(result(status = VpnStatus.Error("Old failure"), generation = 4, failedGeneration = 4))
    }

    @Test
    fun oldConnectedDoesNotCompleteNewAttempt() {
        assertNull(result(status = VpnStatus.Connected, generation = 4))
    }

    @Test
    fun newlyClaimedGenerationCannotReusePreviousConnectedGeneration() {
        assertNull(result(status = VpnStatus.Connected, generation = 5, connectedGeneration = 4))
    }

    @Test
    fun newGenerationWaitsUntilConnected() {
        assertNull(result(status = VpnStatus.Reconnecting, generation = 5))
        assertEquals(true, result(status = VpnStatus.Connected, generation = 5))
    }

    @Test
    fun connectedMustMatchBothActiveAndConfirmedProfile() {
        assertNull(result(status = VpnStatus.Connected, generation = 5, connected = "previous"))
    }

    @Test
    fun fastFailureIsSeenEvenIfPreviousProfileAlreadyRestored() {
        assertEquals(
            false,
            result(
                status = VpnStatus.Connected,
                generation = 6,
                active = "previous",
                connected = "previous",
                failedGeneration = 5
            )
        )
    }

    @Test
    fun newAttemptIgnoresEarlierFailedGeneration() {
        assertEquals(true, result(status = VpnStatus.Connected, generation = 5, failedGeneration = 4))
    }

    @Test
    fun newErrorCompletesAttemptAsFailure() {
        assertEquals(false, result(status = VpnStatus.Error("New failure"), generation = 5))
    }

    @Test
    fun stopAndSupersedingProfileCancelPendingAttempt() {
        assertEquals(false, result(status = VpnStatus.Stopping, generation = 4))
        assertEquals(false, result(status = VpnStatus.Disconnected, generation = 0))
        assertEquals(false, result(status = VpnStatus.Reconnecting, generation = 5, active = "other"))
    }

    private fun canRetry(
        status: VpnStatus,
        target: String? = "failed",
        active: String? = "failed",
        failed: String? = "failed",
        pending: Boolean = false,
        foreground: Boolean = true,
        stopping: Boolean = false
    ) = canRetryNotificationProfile(status, foreground, pending, target, active, failed, stopping)

    private fun result(
        status: VpnStatus,
        generation: Long,
        connectedGeneration: Long = generation,
        active: String? = "failed",
        connected: String? = "failed",
        failedGeneration: Long = -1L
    ) = notificationProfileAttemptResult(
        targetProfileId = "failed",
        afterGeneration = 4,
        generation = generation,
        connectedGeneration = connectedGeneration,
        status = status,
        activeProfileId = active,
        connectedProfileId = connected,
        failedProfileId = "failed",
        failedGeneration = failedGeneration
    )
}
