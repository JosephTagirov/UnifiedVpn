package org.olcbox.app.vpn

data class AndroidSplitTunnelSettings(
    val mode: AndroidSplitTunnelMode = AndroidSplitTunnelMode.AllApps,
    val proxyPackages: Set<String> = emptySet(),
    val bypassPackages: Set<String> = emptySet()
)

data class AndroidSplitTunnelProfiles(
    val olcRtc: AndroidSplitTunnelSettings = AndroidSplitTunnelSettings(),
    val external: AndroidSplitTunnelSettings = AndroidSplitTunnelSettings()
) {
    operator fun get(profile: AndroidSplitTunnelProfile): AndroidSplitTunnelSettings {
        return when (profile) {
            AndroidSplitTunnelProfile.OlcRtc -> olcRtc
            AndroidSplitTunnelProfile.External -> external
        }
    }

    fun updated(
        profile: AndroidSplitTunnelProfile,
        settings: AndroidSplitTunnelSettings
    ): AndroidSplitTunnelProfiles {
        return when (profile) {
            AndroidSplitTunnelProfile.OlcRtc -> copy(olcRtc = settings)
            AndroidSplitTunnelProfile.External -> copy(external = settings)
        }
    }
}

enum class AndroidSplitTunnelProfile {
    OlcRtc,
    External;

    companion object {
        fun fromProfileType(profileType: String?): AndroidSplitTunnelProfile {
            val normalized = profileType?.trim()?.lowercase()
            return if (normalized.isNullOrBlank() || normalized == "olcrtc") OlcRtc else External
        }
    }
}

enum class AndroidSplitTunnelMode(val value: String) {
    AllApps("all_apps"),
    ProxySelected("proxy_selected"),
    BypassSelected("bypass_selected");

    companion object {
        fun fromValue(value: String?): AndroidSplitTunnelMode {
            return entries.firstOrNull { it.value == value } ?: AllApps
        }
    }
}

enum class AndroidSplitTunnelList {
    Proxy,
    Bypass
}

data class AndroidInstalledApp(
    val packageName: String,
    val label: String
)
