package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.olcbox.app.vpn.VpnStatus

class ProfileSwitchRestartPolicyTest {
    @Test
    fun activeAndTransitioningConnectionsRequireRestart() {
        assertTrue(VpnStatus.Connected.shouldRestartAfterProfileSelection())
        assertTrue(VpnStatus.Connecting.shouldRestartAfterProfileSelection())
        assertTrue(VpnStatus.Reconnecting.shouldRestartAfterProfileSelection())
    }

    @Test
    fun stoppedAndFailedConnectionsDoNotRestartWithoutCapturedForce() {
        assertFalse(VpnStatus.Disconnected.shouldRestartAfterProfileSelection())
        assertFalse(VpnStatus.Stopping.shouldRestartAfterProfileSelection())
        assertFalse(VpnStatus.Error("failed").shouldRestartAfterProfileSelection())
    }
}
