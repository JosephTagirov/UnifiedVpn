package org.olcbox.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService

internal object AndroidConnectionPreflight {
    fun requireNoConflict(context: Context, ownsVpn: Boolean, checkSystemProxy: Boolean) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val conflict = try {
            when {
                !ownsVpn && hasVpn(manager) -> ConnectionPreflight.VPN_ACTIVE
                checkSystemProxy && manager.defaultProxy?.let {
                    !it.host.isNullOrBlank() || (it.pacFileUrl != Uri.EMPTY && it.pacFileUrl.toString().isNotBlank())
                } == true -> ConnectionPreflight.PROXY_ACTIVE
                else -> null
            }
        } catch (_: Exception) {
            ConnectionPreflight.CHECK_FAILED
        }
        if (conflict != null) error(conflict)
    }

    fun hasVpn(manager: ConnectivityManager): Boolean = manager.allNetworks.any { network ->
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

/** Avoid presenting Android's replace-VPN permission dialog for a conflicting VPN. */
internal fun prepareUnifiedVpnPermission(context: Context): Intent? {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // The service repeats the authoritative check, including its own TUN ownership,
    // and reports any conflict through the usual connection error state.
    return try {
        if (AndroidConnectionPreflight.hasVpn(manager)) null else VpnService.prepare(context)
    } catch (_: Exception) {
        null
    }
}
