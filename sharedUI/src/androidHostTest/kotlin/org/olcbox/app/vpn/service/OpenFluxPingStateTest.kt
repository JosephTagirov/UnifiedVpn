package org.olcbox.app.vpn.service

import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxPingStateTest {
    @Test
    fun runtimeIdentityIsAvailableOnlyWhileActuallyConnectedAndClearedBeforeTeardown() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_OPENFLUX,
            rawConfig = OpenFluxProfileConfig(
                documentUrl = "https://docs.yandex.ru/docs/view?url=synthetic",
                encryptionKey = "ab".repeat(32)
            ).toJson()
        )
        val proxy = SubscriptionFetchProxy("127.0.0.1", 12345, "fixture-user", "fixture-password")
        try {
            OlcboxVpnState.setStatus(VpnStatus.Connecting, proxy, profile)
            assertNull(OlcboxVpnState.connectedOpenFluxTunnel())
            OlcboxVpnState.setStatus(VpnStatus.Connected, proxy, profile)
            val first = assertNotNull(OlcboxVpnState.connectedOpenFluxTunnel())
            assertTrue(first.matches(profile))

            OlcboxVpnState.setStatus(VpnStatus.Reconnecting, proxy, profile)
            assertNull(OlcboxVpnState.connectedOpenFluxTunnel())
            OlcboxVpnState.setStatus(VpnStatus.Connected, proxy, profile)
            assertNotSame(first, OlcboxVpnState.connectedOpenFluxTunnel())

            OlcboxVpnState.clearConnectedProxy()
            assertNull(OlcboxVpnState.connectedOpenFluxTunnel())
            OlcboxVpnState.setStatus(VpnStatus.Connected, proxy, profile)
            OlcboxVpnState.setStatus(VpnStatus.Stopping)
            assertNull(OlcboxVpnState.connectedOpenFluxTunnel())

            OlcboxVpnState.setStatus(VpnStatus.Connected, proxy, VpnProfileConfig.olcRtc())
            assertNull(OlcboxVpnState.connectedOpenFluxTunnel())
        } finally {
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }
}
