package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationProfileReconnectTest {
    private fun allowed(status: VpnStatus, target: String? = "active", pending: Boolean = false,
        foreground: Boolean = true, stopping: Boolean = false) = canRetryNotificationProfile(
        status = status, foregroundStarted = foreground, requestPending = pending,
        targetProfileId = target, activeProfileId = "active", failedProfileId = "other-failed",
        lifecycleStopping = stopping, reconnect = true
    )
    @Test fun connectedProfileCanBeReconnectedWithoutAnError() {
        assertTrue(allowed(VpnStatus.Connected))
        assertTrue(allowed(VpnStatus.Error("synthetic")))
    }
    @Test fun reconnectCannotSilentlyChooseADifferentProfile() {
        assertFalse(allowed(VpnStatus.Connected, target = "other-failed"))
        assertFalse(allowed(VpnStatus.Connected, target = null))
    }
    @Test fun duplicateAndBusyRequestsAreRejected() {
        assertFalse(allowed(VpnStatus.Connected, pending = true))
        listOf(VpnStatus.Connecting, VpnStatus.Reconnecting, VpnStatus.Stopping, VpnStatus.Disconnected)
            .forEach { assertFalse(allowed(it)) }
    }
    @Test fun stoppedLifecycleAndMissingForegroundCannotRestartTheVpn() {
        assertFalse(allowed(VpnStatus.Connected, foreground = false))
        assertFalse(allowed(VpnStatus.Connected, stopping = true))
    }
    @Test fun ordinaryFailureRetryKeepsItsOriginalTargetGuard() {
        assertFalse(canRetryNotificationProfile(VpnStatus.Connected, true, false,
            "active", "active", "other-failed"))
        assertTrue(canRetryNotificationProfile(VpnStatus.Connected, true, false,
            "other-failed", "active", "other-failed"))
    }
}
