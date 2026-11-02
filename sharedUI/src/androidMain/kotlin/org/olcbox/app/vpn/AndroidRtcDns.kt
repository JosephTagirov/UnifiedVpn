package org.olcbox.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Resolve RTC signaling with the underlying network's DNS, including carrier DNS64. */
internal fun androidRtcDnsEndpoint(context: Context, configured: String): String {
    val networkDns = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val candidates = listOfNotNull(connectivity.boundNetworkForProcess, connectivity.activeNetwork) +
            connectivity.allNetworks.toList()
        candidates.distinct().asSequence()
            .filter { network -> connectivity.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true }
            .flatMap { connectivity.getLinkProperties(it)?.dnsServers.orEmpty().asSequence() }
            .mapNotNull { it.hostAddress }.firstOrNull()
    }.getOrNull()
    return selectOlcRtcDnsEndpoint(configured, networkDns ?: "1.1.1.1:53")
}
