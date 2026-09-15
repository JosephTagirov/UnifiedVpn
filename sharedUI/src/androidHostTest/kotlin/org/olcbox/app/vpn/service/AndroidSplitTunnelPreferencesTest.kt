package org.olcbox.app.vpn.service

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelProfile
import org.olcbox.app.vpn.AndroidSplitTunnelProfiles
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSplitTunnelPreferencesTest {
    private val sharedSettings = AndroidSplitTunnelSettings(
        mode = AndroidSplitTunnelMode.ProxySelected,
        proxyPackages = setOf("example.shared.proxied"),
        bypassPackages = setOf("example.shared.bypassed")
    )
    private val externalSettings = AndroidSplitTunnelSettings(
        mode = AndroidSplitTunnelMode.BypassSelected,
        proxyPackages = setOf("example.external.proxied"),
        bypassPackages = setOf("example.external.bypassed")
    )
    private val legacySettings = AndroidSplitTunnelSettings(
        mode = AndroidSplitTunnelMode.BypassSelected,
        proxyPackages = setOf("example.legacy.proxied"),
        bypassPackages = setOf("example.legacy.bypassed")
    )

    @Test
    fun notificationRefreshReplacesStaleSharedRulesForBothTransports() = runTest {
        val preferences = MutableStateFlow(preferencesFor())
        var cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)
        val updated = sharedSettings.copy(
            mode = AndroidSplitTunnelMode.BypassSelected,
            proxyPackages = setOf("example.shared.new.proxied"),
            bypassPackages = setOf("example.shared.new.bypassed")
        )
        preferences.value = preferencesFor(shared = updated)
        assertEquals(sharedSettings, cachedProfiles.olcRtc)

        cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)

        for (type in listOf(VpnProfileConfig.TYPE_OLCRTC, VpnProfileConfig.TYPE_OPENFLUX)) {
            assertEquals(updated, cachedProfiles[AndroidSplitTunnelProfile.fromProfileType(type)])
        }
        assertEquals(externalSettings, cachedProfiles.external)
    }

    @Test
    fun notificationRefreshKeepsExternalEditsIsolatedFromBothSharedTransports() = runTest {
        val preferences = MutableStateFlow(preferencesFor())
        var cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)
        val updated = externalSettings.copy(
            mode = AndroidSplitTunnelMode.ProxySelected,
            proxyPackages = setOf("example.external.new")
        )
        preferences.value = preferencesFor(external = updated)
        assertEquals(externalSettings, cachedProfiles.external)

        cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)

        for (type in listOf(VpnProfileConfig.TYPE_OLCRTC, VpnProfileConfig.TYPE_OPENFLUX)) {
            assertEquals(sharedSettings, cachedProfiles[AndroidSplitTunnelProfile.fromProfileType(type)])
        }
        for (type in listOf(VpnProfileConfig.TYPE_VLESS, VpnProfileConfig.TYPE_AMNEZIA_WG,
            VpnProfileConfig.TYPE_AMNEZIA_VPN)) {
            assertEquals(updated, cachedProfiles[AndroidSplitTunnelProfile.fromProfileType(type)])
        }
    }

    @Test
    fun notificationRefreshPreservesEmptySelectionsAndEveryRoutingMode() = runTest {
        for (mode in AndroidSplitTunnelMode.entries) {
            val emptyShared = AndroidSplitTunnelSettings(mode = mode)
            val emptyExternal = AndroidSplitTunnelSettings(mode = mode)
            val preferences = MutableStateFlow(preferencesFor())
            var cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)
            assertEquals(sharedSettings, cachedProfiles.olcRtc)
            preferences.value = preferencesFor(emptyShared, emptyExternal)

            cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)

            assertEquals(AndroidSplitTunnelProfiles(emptyShared, emptyExternal), cachedProfiles)
        }
    }

    @Test
    fun rollbackRefreshReadsAgainInsteadOfReusingTheFailedSwitchSnapshot() = runTest {
        val preferences = MutableStateFlow(preferencesFor())
        val failedSwitchSnapshot = loadAndroidSplitTunnelProfiles(preferences)
        val updated = sharedSettings.copy(proxyPackages = setOf("example.shared.rollback"))
        preferences.value = preferencesFor(shared = updated)

        val rollbackSnapshot = loadAndroidSplitTunnelProfiles(preferences)

        assertEquals(sharedSettings, failedSwitchSnapshot.olcRtc)
        assertEquals(updated, rollbackSnapshot.olcRtc)
        assertEquals(externalSettings, rollbackSnapshot.external)
    }

    @Test
    fun normalStartKeepsPerGroupIntentExtrasAheadOfSavedPreferences() {
        val strings = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_MODE to AndroidSplitTunnelMode.AllApps.value,
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_MODE to AndroidSplitTunnelMode.ProxySelected.value
        )
        val packages = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_PROXY_APPS to setOf("example.intent.shared.proxy"),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS to setOf("example.intent.shared.bypass"),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS to setOf("example.intent.external.proxy"),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS to setOf("example.intent.external.bypass")
        )

        val profiles = resolveAndroidSplitTunnelProfiles(preferencesFor(), strings::get, packages::get)

        assertEquals(
            AndroidSplitTunnelSettings(AndroidSplitTunnelMode.AllApps,
                setOf("example.intent.shared.proxy"), setOf("example.intent.shared.bypass")),
            profiles.olcRtc
        )
        assertEquals(
            AndroidSplitTunnelSettings(AndroidSplitTunnelMode.ProxySelected,
                setOf("example.intent.external.proxy"), setOf("example.intent.external.bypass")),
            profiles.external
        )
    }

    @Test
    fun normalStartKeepsExplicitEmptyIntentListsAheadOfSavedLists() {
        val packages = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_PROXY_APPS to emptySet<String>(),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS to emptySet<String>(),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS to emptySet<String>(),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS to emptySet<String>()
        )

        val profiles = resolveAndroidSplitTunnelProfiles(preferencesFor(), packagesExtra = packages::get)

        assertEquals(AndroidSplitTunnelSettings(mode = sharedSettings.mode), profiles.olcRtc)
        assertEquals(AndroidSplitTunnelSettings(mode = externalSettings.mode), profiles.external)
    }

    @Test
    fun normalStartKeepsPerGroupPreferencesAheadOfLegacyIntentExtras() {
        val strings = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE to AndroidSplitTunnelMode.AllApps.value
        )
        val packages = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS to emptySet<String>(),
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS to emptySet<String>()
        )
        val profiles = resolveAndroidSplitTunnelProfiles(
            preferences = preferencesFor(),
            stringExtra = strings::get,
            packagesExtra = packages::get
        )

        assertEquals(AndroidSplitTunnelProfiles(sharedSettings, externalSettings), profiles)
    }

    @Test
    fun normalStartKeepsLegacyIntentExtrasAheadOfLegacyPreferences() {
        val preferences = preferencesOf(
            KEY_ANDROID_SPLIT_TUNNEL_MODE to legacySettings.mode.value,
            KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS to legacySettings.proxyPackages,
            KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS to legacySettings.bypassPackages
        )
        val strings = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE to sharedSettings.mode.value
        )
        val packages = mapOf(
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS to sharedSettings.proxyPackages,
            OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS to sharedSettings.bypassPackages
        )

        val profiles = resolveAndroidSplitTunnelProfiles(preferences, strings::get, packages::get)

        assertEquals(AndroidSplitTunnelProfiles(sharedSettings, sharedSettings), profiles)
    }

    @Test
    fun missingPerGroupFieldsKeepTheExistingLegacyFallback() {
        val preferences = preferencesOf(
            KEY_ANDROID_SPLIT_TUNNEL_MODE to legacySettings.mode.value,
            KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS to legacySettings.proxyPackages,
            KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS to legacySettings.bypassPackages,
            KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_MODE to sharedSettings.mode.value,
            KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS to emptySet<String>()
        )

        val profiles = resolveAndroidSplitTunnelProfiles(preferences)

        assertEquals(legacySettings.copy(mode = sharedSettings.mode), profiles.olcRtc)
        assertEquals(legacySettings.copy(bypassPackages = emptySet()), profiles.external)
    }

    @Test
    fun normalStartKeepsDefaultsWhenPreferencesAndExtrasAreAbsent() {
        assertEquals(AndroidSplitTunnelProfiles(), resolveAndroidSplitTunnelProfiles(null))
    }

    @Test
    fun failedRefreshDoesNotReplaceTheCacheWithAllApps() = runTest {
        val original = AndroidSplitTunnelProfiles(sharedSettings, externalSettings)
        var cachedProfiles = original
        val failure = IOException("Synthetic preference read failure")

        val thrown = assertFailsWith<IOException> {
            cachedProfiles = loadAndroidSplitTunnelProfiles(flow { throw failure })
        }

        assertSame(failure, thrown)
        assertSame(original, cachedProfiles)
    }

    @Test
    fun cancelledRefreshDoesNotPublishSettingsOrKeepCollecting() = runTest {
        val original = AndroidSplitTunnelProfiles(sharedSettings, externalSettings)
        var cachedProfiles = original
        val preferences = MutableSharedFlow<Preferences>()
        val refresh = launch {
            cachedProfiles = loadAndroidSplitTunnelProfiles(preferences)
        }
        runCurrent()
        assertFalse(refresh.isCompleted)
        assertEquals(1, preferences.subscriptionCount.value)

        refresh.cancelAndJoin()

        assertSame(original, cachedProfiles)
        assertEquals(0, preferences.subscriptionCount.value)
    }

    @Test
    fun refreshReadsOnlyTheFirstPreferenceSnapshot() = runTest {
        val profiles = loadAndroidSplitTunnelProfiles(flow {
            emit(preferencesFor())
            error("The startup must not keep observing preferences")
        })

        assertEquals(AndroidSplitTunnelProfiles(sharedSettings, externalSettings), profiles)
    }

    private fun preferencesFor(
        shared: AndroidSplitTunnelSettings = sharedSettings,
        external: AndroidSplitTunnelSettings = externalSettings
    ): Preferences = preferencesOf(
        KEY_ANDROID_SPLIT_TUNNEL_MODE to legacySettings.mode.value,
        KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS to legacySettings.proxyPackages,
        KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS to legacySettings.bypassPackages,
        KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_MODE to shared.mode.value,
        KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_PROXY_APPS to shared.proxyPackages,
        KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS to shared.bypassPackages,
        KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_MODE to external.mode.value,
        KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS to external.proxyPackages,
        KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS to external.bypassPackages
    )
}
