package org.olcbox.app.vpn

import org.olcbox.app.data.model.VpnProfileConfig
import java.net.NetworkInterface

/** Starting the same WG identity twice would roam the server peer away from the live VPN. */
internal object ExternalAwgGuard {
    fun requireNotConnected(profile: VpnProfileConfig) {
        if(profile.normalizedType !in setOf(VpnProfileConfig.TYPE_AMNEZIA_WG,VpnProfileConfig.TYPE_AMNEZIA_VPN)) return
        val config=VpnProfileReachability.wireGuardText(profile) ?: return
        val addresses=config.lineSequence().filter { it.substringBefore('=').trim().equals("Address",true) }
            .flatMap { it.substringAfter('=').split(',').asSequence() }.map { it.trim().substringBefore('/') }.toSet()
        val live=runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet() }.getOrDefault(emptySet())
        if(addresses.any { it in live }) throw VpnPingUnavailableException("This AWG profile is active in another VPN app")
    }
}
