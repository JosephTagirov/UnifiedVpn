package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Path

internal object DesktopOpenFluxConfig {
    fun build(profile: VpnProfileConfig, socks: DesktopSocksProxySettings): String {
        val normalized = profile.normalized()
        require(normalized.isOpenFlux()) { "OpenFlux requires an OpenFlux profile" }
        val config = OpenFluxProfileConfig.parse(
            normalized.rawConfig?.takeIf(String::isNotBlank) ?: normalized.uri.orEmpty()
        ) ?: error("OpenFlux profile is invalid")
        require(config.isValid()) { "OpenFlux requires a valid document URL and encryption key" }

        val settings = socks.normalized()
        require(settings.host == PacServer.LOCAL_SOCKS_HOST) {
            "OpenFlux SOCKS must listen on 127.0.0.1"
        }
        require(
            settings.username.isBlank() && settings.password.isBlank() ||
                settings.username.isNotBlank() && settings.password.isNotBlank()
        ) { "OpenFlux SOCKS authentication requires both a username and password" }

        return config.toEngineJson(
            mode = "client",
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun args(binary: Path, configPath: Path): List<String> = listOf(
        binary.toAbsolutePath().toString(),
        "--config",
        configPath.toAbsolutePath().toString()
    )
}
