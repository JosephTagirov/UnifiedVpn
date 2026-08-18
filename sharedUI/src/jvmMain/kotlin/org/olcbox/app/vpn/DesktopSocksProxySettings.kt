package org.olcbox.app.vpn

import kotlinx.serialization.Serializable
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.PacServer

@Serializable
enum class DesktopRoutingMode {
    Auto,
    Tun,
    SystemProxy,
    LocalSocks;

    fun displayName(): String = when (this) {
        Auto -> "Auto"
        Tun -> "TUN"
        SystemProxy -> "System proxy"
        LocalSocks -> "Local SOCKS only"
    }

    fun description(): String = when (this) {
        Auto -> "Use the recommended mode for this operating system"
        Tun -> "Route system traffic through a virtual network adapter"
        SystemProxy -> "Configure the operating system proxy automatically"
        LocalSocks -> "Expose SOCKS5 without changing system routing"
    }

    fun effectiveDisplayName(): String = when (resolveForCurrentPlatform()) {
        Tun -> "TUN"
        SystemProxy -> "System proxy"
        LocalSocks -> "Local SOCKS only"
        Auto -> error("Auto must resolve to a concrete desktop routing mode")
    }

    fun effectiveMode(): DesktopRoutingMode = resolveForCurrentPlatform()

    internal fun resolveForCurrentPlatform(): DesktopRoutingMode {
        if (this != Auto) return this
        return when (DesktopPaths.os) {
            DesktopOs.Linux,
            DesktopOs.Windows -> Tun
            DesktopOs.MacOS -> SystemProxy
            DesktopOs.Other -> LocalSocks
        }
    }

    companion object {
        fun availableForCurrentPlatform(): List<DesktopRoutingMode> = buildList {
            add(Auto)
            if (DesktopPaths.os == DesktopOs.Linux || DesktopPaths.os == DesktopOs.Windows) {
                add(Tun)
            }
            if (DesktopPaths.os == DesktopOs.MacOS || DesktopPaths.os == DesktopOs.Windows) {
                add(SystemProxy)
            }
            add(LocalSocks)
        }
    }
}

@Serializable
data class DesktopSocksProxySettings(
    val host: String = PacServer.LOCAL_SOCKS_HOST,
    val port: Int = PacServer.LOCAL_SOCKS_PORT,
    val username: String = "",
    val password: String = "",
    val routingMode: DesktopRoutingMode = DesktopRoutingMode.Auto
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun normalized(): DesktopSocksProxySettings {
        return copy(
            host = host.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = sanitizePort(port),
            username = username.take(MAX_CREDENTIAL_LENGTH),
            password = password.take(MAX_CREDENTIAL_LENGTH),
            routingMode = routingMode.takeIf { it in DesktopRoutingMode.availableForCurrentPlatform() }
                ?: DesktopRoutingMode.Auto
        )
    }

    companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_CREDENTIAL_LENGTH = 64

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        fun sanitizePort(port: Int?): Int {
            return port?.takeIf { isValidPort(it) } ?: PacServer.LOCAL_SOCKS_PORT
        }
    }
}
