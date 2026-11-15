package org.olcbox.app.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.olcbox.app.vpn.ManagedEntryDns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Resolve only the owned entry through the selected physical Android network, before creating its core. */
internal fun resolveAndroidManagedPrimary(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return ManagedEntryDns.FALLBACK_PRIMARY_IP
    val network = manager.boundNetworkForProcess
        ?: manager.activeNetwork?.takeIf { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true }
        ?: manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
        ?: return ManagedEntryDns.FALLBACK_PRIMARY_IP
    return ManagedEntryDns.resolve { server, request ->
        DatagramSocket(null).use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = ManagedEntryDns.QUERY_TIMEOUT_MS
            val address = InetAddress.getByAddress(server.split('.').map { it.toInt().toByte() }.toByteArray())
            socket.connect(InetSocketAddress(address, 53))
            socket.send(DatagramPacket(request, request.size))
            val reply = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(reply)
            reply.data.copyOfRange(reply.offset, reply.offset + reply.length)
        }
    }
}
