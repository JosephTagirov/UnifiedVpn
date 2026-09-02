package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.net.InetSocketAddress
import java.net.ServerSocket

internal object LocalPortAllocator {
    private const val SEARCH_ATTEMPTS = 100
    private const val EPHEMERAL_ATTEMPTS = 10

    fun select(
        host: String,
        preferredPort: Int,
        excludedPorts: Set<Int> = emptySet()
    ): Int {
        repeat(SEARCH_ATTEMPTS) { offset ->
            val candidate = wrapPort(preferredPort, offset)
            if (candidate !in excludedPorts && canBind(host, candidate)) {
                return candidate
            }
        }

        repeat(EPHEMERAL_ATTEMPTS) {
            val candidate = bindEphemeral(host)
            if (candidate !in excludedPorts) return candidate
        }
        error("No free local TCP port is available")
    }

    internal fun canBind(host: String, port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(host, port))
        }
    }.isSuccess

    private fun bindEphemeral(host: String): Int {
        return ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(host, 0))
            socket.localPort
        }
    }

    private fun wrapPort(preferredPort: Int, offset: Int): Int {
        val range = DesktopSocksProxySettings.MAX_PORT - DesktopSocksProxySettings.MIN_PORT + 1
        return DesktopSocksProxySettings.MIN_PORT +
            (preferredPort - DesktopSocksProxySettings.MIN_PORT + offset) % range
    }
}
