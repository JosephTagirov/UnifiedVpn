package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.vpn.VpnEntryFailoverPlanner

/** Decide entry ownership and every server bypass before the Windows capture routes are installed. */
internal data class DesktopEntryRouting(
    val applyDirectRouting: Boolean,
    val entries: VpnEntryFailoverConfig?,
    val bypassHosts: List<String>
) {
    companion object {
        fun plan(profile: VpnProfileConfig, wireGuardConfig: String?, endpointHosts: List<String>,
                 os: DesktopOs, probeOnly: Boolean, separateDownloadEndpoint: Boolean): DesktopEntryRouting {
            val direct = !probeOnly && os == DesktopOs.Windows && profile.normalizedType in setOf(
                VpnProfileConfig.TYPE_VLESS, VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)
            val entries = if (direct && !separateDownloadEndpoint)
                VpnEntryFailoverPlanner.plan(profile, wireGuardConfig) else null
            // The saved hostname remains the TLS/WS identity, but is not dialed by a paired core.
            // Resolving it here would make a healthy literal backup depend on the primary alias DNS.
            val bypassHosts = entries?.let { listOf(it.primary.host, it.backup.host) } ?: endpointHosts
            return DesktopEntryRouting(direct, entries, bypassHosts.distinct())
        }
    }
}
