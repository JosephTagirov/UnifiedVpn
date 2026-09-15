package org.olcbox.app.vpn

import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidSplitTunnelSettingsTest {
    private val rtcSettings = AndroidSplitTunnelSettings(
        mode = AndroidSplitTunnelMode.ProxySelected,
        proxyPackages = setOf("example.rtc.proxied"),
        bypassPackages = setOf("example.rtc.bypassed")
    )
    private val externalSettings = AndroidSplitTunnelSettings(
        mode = AndroidSplitTunnelMode.BypassSelected,
        proxyPackages = setOf("example.external.proxied"),
        bypassPackages = setOf("example.external.bypassed")
    )
    private val profiles = AndroidSplitTunnelProfiles(rtcSettings, externalSettings)

    @Test
    fun openFluxAndOlcRtcResolveToTheSameGroup() {
        for (type in listOf("olcrtc", "openflux", "  OpenFlux  ", " OLCrtc ")) {
            assertEquals(AndroidSplitTunnelProfile.OlcRtc, AndroidSplitTunnelProfile.fromProfileType(type))
        }
    }

    @Test
    fun absentTypeKeepsTheLegacyOlcRtcDefault() {
        for (type in listOf(null, "", " \t\n")) {
            assertEquals(AndroidSplitTunnelProfile.OlcRtc, AndroidSplitTunnelProfile.fromProfileType(type))
        }
    }

    @Test
    fun vlessAndAmneziaRemainSeparate() {
        for (type in listOf(VpnProfileConfig.TYPE_VLESS, VpnProfileConfig.TYPE_AMNEZIA_WG,
            VpnProfileConfig.TYPE_AMNEZIA_VPN, " VLESS ")) {
            assertEquals(AndroidSplitTunnelProfile.External, AndroidSplitTunnelProfile.fromProfileType(type))
            assertEquals(externalSettings, profiles[AndroidSplitTunnelProfile.fromProfileType(type)])
        }
    }

    @Test
    fun unknownTypeKeepsTheExternalFallback() {
        assertEquals(AndroidSplitTunnelProfile.External, AndroidSplitTunnelProfile.fromProfileType("unknown"))
    }

    @Test
    fun openFluxUsesExistingOlcRtcRulesWithoutMergingExternalLists() {
        val selected = profiles[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OPENFLUX)]

        assertEquals(rtcSettings, selected)
        assertFalse(selected.proxyPackages.any { it in externalSettings.proxyPackages })
        assertFalse(selected.bypassPackages.any { it in externalSettings.bypassPackages })
    }

    @Test
    fun editsFromOpenFluxApplyToOlcRtcButNotExternalProfiles() {
        val updatedSettings = rtcSettings.copy(
            mode = AndroidSplitTunnelMode.BypassSelected,
            bypassPackages = setOf("example.shared.bypassed")
        )
        val updated = profiles.updated(
            AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OPENFLUX), updatedSettings
        )

        assertEquals(updatedSettings, updated[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OLCRTC)])
        assertEquals(updatedSettings, updated[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OPENFLUX)])
        assertEquals(externalSettings, updated.external)
        assertEquals(rtcSettings, profiles.olcRtc)
    }

    @Test
    fun externalEditsDoNotChangeSharedOlcRtcOpenFluxRules() {
        val updatedSettings = externalSettings.copy(proxyPackages = setOf("example.external.new"))
        val updated = profiles.updated(AndroidSplitTunnelProfile.External, updatedSettings)

        assertEquals(updatedSettings, updated.external)
        assertEquals(rtcSettings, updated[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OPENFLUX)])
        assertEquals(rtcSettings, updated.olcRtc)
    }

    @Test
    fun emptySelectedListDoesNotFallBackToExternalOrAllApps() {
        val emptySelection = rtcSettings.copy(proxyPackages = emptySet())
        val updated = profiles.copy(olcRtc = emptySelection)
        val selected = updated[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OPENFLUX)]

        assertEquals(AndroidSplitTunnelMode.ProxySelected, selected.mode)
        assertEquals(emptySet(), selected.proxyPackages)
        assertEquals(emptySelection.bypassPackages, selected.bypassPackages)
    }

    @Test
    fun allRoutingModesShareTheSameRulesForBothTransports() {
        for (mode in AndroidSplitTunnelMode.entries) {
            val settings = rtcSettings.copy(mode = mode)
            val updated = profiles.copy(olcRtc = settings)
            assertEquals(settings, updated[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OPENFLUX)])
            assertEquals(settings, updated[AndroidSplitTunnelProfile.fromProfileType(VpnProfileConfig.TYPE_OLCRTC)])
        }
    }

    @Test
    fun sharingRoutingRulesDoesNotReclassifyTheNativeEngine() {
        val profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX)

        assertFalse(profile.isOlcRtc())
        assertEquals(AndroidSplitTunnelProfile.OlcRtc, AndroidSplitTunnelProfile.fromProfileType(profile.normalizedType))
    }
}
