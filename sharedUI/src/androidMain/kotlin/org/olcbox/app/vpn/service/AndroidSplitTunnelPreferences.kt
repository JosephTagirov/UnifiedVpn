package org.olcbox.app.vpn.service

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.olcbox.app.vpn.AndroidSplitTunnelMode
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
import kotlin.coroutines.coroutineContext

internal suspend fun loadAndroidSplitTunnelProfiles(
    preferences: Flow<Preferences>
): AndroidSplitTunnelProfiles {
    val latestPreferences = preferences.first()
    coroutineContext.ensureActive()
    return resolveAndroidSplitTunnelProfiles(latestPreferences)
}

internal fun resolveAndroidSplitTunnelProfiles(
    preferences: Preferences?,
    stringExtra: (String) -> String? = { null },
    packagesExtra: (String) -> Set<String>? = { null }
): AndroidSplitTunnelProfiles {
    val legacySplitTunnel = AndroidSplitTunnelSettings(
        mode = AndroidSplitTunnelMode.fromValue(
            stringExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE)
                ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
        ),
        proxyPackages = packagesExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS)
            ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty(),
        bypassPackages = packagesExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS)
            ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty()
    )

    fun settings(
        modeExtra: String,
        proxyAppsExtra: String,
        bypassAppsExtra: String,
        modeKey: Preferences.Key<String>,
        proxyAppsKey: Preferences.Key<Set<String>>,
        bypassAppsKey: Preferences.Key<Set<String>>
    ): AndroidSplitTunnelSettings {
        return AndroidSplitTunnelSettings(
            mode = AndroidSplitTunnelMode.fromValue(
                stringExtra(modeExtra)
                    ?: preferences?.get(modeKey)
                    ?: legacySplitTunnel.mode.value
            ),
            proxyPackages = packagesExtra(proxyAppsExtra)
                ?: preferences?.get(proxyAppsKey)
                ?: legacySplitTunnel.proxyPackages,
            bypassPackages = packagesExtra(bypassAppsExtra)
                ?: preferences?.get(bypassAppsKey)
                ?: legacySplitTunnel.bypassPackages
        )
    }

    return AndroidSplitTunnelProfiles(
        olcRtc = settings(
            modeExtra = OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_MODE,
            proxyAppsExtra = OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_PROXY_APPS,
            bypassAppsExtra = OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS,
            modeKey = KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_MODE,
            proxyAppsKey = KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_PROXY_APPS,
            bypassAppsKey = KEY_ANDROID_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS
        ),
        external = settings(
            modeExtra = OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_MODE,
            proxyAppsExtra = OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS,
            bypassAppsExtra = OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS,
            modeKey = KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_MODE,
            proxyAppsKey = KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS,
            bypassAppsKey = KEY_ANDROID_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS
        )
    )
}
