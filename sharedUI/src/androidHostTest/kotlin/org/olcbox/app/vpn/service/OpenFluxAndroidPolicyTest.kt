package org.olcbox.app.vpn.service

import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.isTransportSupportedOnCurrentPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxAndroidPolicyTest {
    @Test
    fun addingOpenFluxKeepsAndroidOlcRtcTransportRestrictions() {
        assertTrue(isTransportSupportedOnCurrentPlatform(LocationConfig.TRANSPORT_DATACHANNEL))
        assertTrue(isTransportSupportedOnCurrentPlatform(LocationConfig.TRANSPORT_VP8CHANNEL))
        assertFalse(isTransportSupportedOnCurrentPlatform(LocationConfig.TRANSPORT_SEICHANNEL))
    }

    @Test
    fun nativeEncryptedProfilesStartTheirOwnCoreInsteadOfUsingAnExistingProxy() {
        val profile = VpnProfileConfig(
            type = " OpenFlux ",
            localSocksHost = "127.0.0.1",
            localSocksPort = 23841
        )

        assertNull(existingProfileSocksPort(profile))
        assertNull(existingProfileSocksPort(profile.copy(type = VpnProfileConfig.TYPE_VMESS)))
        assertNull(existingProfileSocksPort(profile.copy(type = VpnProfileConfig.TYPE_HYSTERIA2)))
        assertEquals(23841, existingProfileSocksPort(profile.copy(type = VpnProfileConfig.TYPE_VLESS)))
    }

    @Test
    fun proxyProfilesUseTheTunDnsBridgeWithoutChangingWireGuardPolicy() {
        assertTrue(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_OPENFLUX))
        assertTrue(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_OLCRTC))
        assertTrue(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_VLESS))
        assertTrue(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_VMESS))
        assertTrue(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_HYSTERIA2))
        assertFalse(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_AMNEZIA_WG))
        assertFalse(profileRequiresTunSocksBridge(VpnProfileConfig.TYPE_AMNEZIA_VPN))
    }
}
