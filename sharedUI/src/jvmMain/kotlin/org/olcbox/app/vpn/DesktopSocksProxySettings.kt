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
        Tun -> "TUN (VPN)"
        SystemProxy -> "System proxy"
        LocalSocks -> "Local SOCKS only"
    }

    fun description(): String = when (this) {
        Auto -> "Use local SOCKS for olcRTC and the recommended mode for other profiles"
        Tun -> "Route all traffic through a virtual adapter; Windows asks for administrator rights"
        SystemProxy -> "Configure the operating system proxy automatically"
        LocalSocks -> "Expose SOCKS5 without changing system routing"
    }

    fun effectiveDisplayName(isOlcRtcProfile: Boolean = false): String =
        when (resolveForCurrentPlatform(isOlcRtcProfile)) {
            Tun -> "TUN (VPN)"
            SystemProxy -> "System proxy"
            LocalSocks -> "Local SOCKS only"
            Auto -> error("Auto must resolve to a concrete desktop routing mode")
        }

    fun effectiveMode(isOlcRtcProfile: Boolean = false): DesktopRoutingMode =
        resolveForCurrentPlatform(isOlcRtcProfile)

    internal fun resolveForCurrentPlatform(isOlcRtcProfile: Boolean = false): DesktopRoutingMode =
        resolveFor(DesktopPaths.os, isOlcRtcProfile)

    internal fun resolveFor(os: DesktopOs): DesktopRoutingMode = resolveFor(os, false)

    internal fun resolveFor(os: DesktopOs, isOlcRtcProfile: Boolean): DesktopRoutingMode {
        if (this != Auto) return this
        if (isOlcRtcProfile) return LocalSocks
        return when (os) {
            DesktopOs.Linux -> Tun
            DesktopOs.Windows -> SystemProxy
            DesktopOs.Other -> LocalSocks
        }
    }

    companion object {
        fun availableForCurrentPlatform(): List<DesktopRoutingMode> = buildList {
            add(Auto)
            if (DesktopPaths.os == DesktopOs.Linux || DesktopPaths.os == DesktopOs.Windows) {
                add(Tun)
            }
            if (DesktopPaths.os == DesktopOs.Windows) {
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
    val routingMode: DesktopRoutingMode = DesktopRoutingMode.Auto,
    val externalRoutingMode: DesktopRoutingMode = DesktopRoutingMode.Auto
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun normalized(): DesktopSocksProxySettings {
        val availableModes = DesktopRoutingMode.availableForCurrentPlatform()
        return copy(
            host = host.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = sanitizePort(port),
            username = username.take(MAX_CREDENTIAL_LENGTH),
            password = password.take(MAX_CREDENTIAL_LENGTH),
            routingMode = routingMode.takeIf { it in availableModes } ?: DesktopRoutingMode.Auto,
            externalRoutingMode = externalRoutingMode.takeIf { it in availableModes }
                ?: DesktopRoutingMode.Auto
        )
    }

    fun routingModeFor(isOlcRtcProfile: Boolean): DesktopRoutingMode =
        if (isOlcRtcProfile) routingMode else externalRoutingMode

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
