package org.olcbox.app.vpn

import kotlinx.serialization.Serializable
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.PacServer

// Keep Android and Linux policy unchanged. On Windows these transports must
// never inherit the TUN choice saved for a VLESS / Amnezia profile.
fun VpnProfileConfig?.usesProxyRoutingSettings(): Boolean = usesProxyRoutingSettings(DesktopPaths.os)

internal fun VpnProfileConfig?.usesProxyRoutingSettings(os: DesktopOs): Boolean =
    this == null || isOlcRtc() || (os == DesktopOs.Windows && isOpenFlux())

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
        Auto -> "Use local SOCKS for olcRTC / OpenFlux on Windows and the recommended mode for other profiles"
        Tun -> "Route all traffic through a virtual adapter"
        SystemProxy -> "Configure the operating system proxy automatically"
        LocalSocks -> "Expose SOCKS5 without changing system routing"
    }

    fun effectiveDisplayName(usesProxySettings: Boolean = false): String =
        when (resolveForCurrentPlatform(usesProxySettings)) {
            Tun -> "TUN (VPN)"
            SystemProxy -> "System proxy"
            LocalSocks -> "Local SOCKS only"
            Auto -> error("Auto must resolve to a concrete desktop routing mode")
        }

    fun effectiveMode(usesProxySettings: Boolean = false): DesktopRoutingMode =
        resolveForCurrentPlatform(usesProxySettings)

    internal fun resolveForCurrentPlatform(usesProxySettings: Boolean = false): DesktopRoutingMode =
        resolveFor(DesktopPaths.os, usesProxySettings)

    internal fun resolveFor(os: DesktopOs): DesktopRoutingMode = resolveFor(os, false)

    internal fun resolveFor(os: DesktopOs, usesProxySettings: Boolean): DesktopRoutingMode {
        if (this != Auto) {
            return if (this == Tun && os == DesktopOs.Windows && usesProxySettings) LocalSocks else this
        }
        if (usesProxySettings) return LocalSocks
        return when (os) {
            DesktopOs.Linux -> Tun
            DesktopOs.Windows -> SystemProxy
            DesktopOs.Other -> LocalSocks
        }
    }

    companion object {
        fun availableForCurrentPlatform(usesProxySettings: Boolean = false): List<DesktopRoutingMode> =
            availableFor(DesktopPaths.os, usesProxySettings)

        internal fun availableFor(os: DesktopOs, usesProxySettings: Boolean): List<DesktopRoutingMode> = buildList {
            add(Auto)
            if (os == DesktopOs.Linux || (os == DesktopOs.Windows && !usesProxySettings)) {
                add(Tun)
            }
            if (os == DesktopOs.Windows) {
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

    fun normalized(): DesktopSocksProxySettings = normalizedFor(DesktopPaths.os)

    internal fun normalizedFor(os: DesktopOs): DesktopSocksProxySettings {
        val proxyModes = DesktopRoutingMode.availableFor(os, usesProxySettings = true)
        val externalModes = DesktopRoutingMode.availableFor(os, usesProxySettings = false)
        return copy(
            host = host.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = sanitizePort(port),
            username = username.take(MAX_CREDENTIAL_LENGTH),
            password = password.take(MAX_CREDENTIAL_LENGTH),
            routingMode = routingMode.takeIf { it in proxyModes } ?: DesktopRoutingMode.Auto,
            externalRoutingMode = externalRoutingMode.takeIf { it in externalModes }
                ?: DesktopRoutingMode.Auto
        )
    }

    fun routingModeFor(usesProxySettings: Boolean): DesktopRoutingMode =
        if (usesProxySettings) routingMode else externalRoutingMode

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
