package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.XraySocksSettings
import org.olcbox.app.vpn.XrayVlessConfig

internal object DesktopXrayConfig {
    fun supports(profile: VpnProfileConfig) = XrayVlessConfig.supports(profile)
    fun endpointHost(profile: VpnProfileConfig) = XrayVlessConfig.endpointHost(profile)
    fun downloadEndpointHost(profile: VpnProfileConfig) = XrayVlessConfig.downloadEndpointHost(profile)
    fun endpointHosts(profile: VpnProfileConfig) = XrayVlessConfig.endpointHosts(profile)
    fun build(profile: VpnProfileConfig, socks: DesktopSocksProxySettings,
              endpointAddress: String? = null, downloadEndpointAddress: String? = null): String {
        val settings = socks.normalized()
        return XrayVlessConfig.build(profile, XraySocksSettings(settings.host, settings.port,
            settings.username, settings.password), endpointAddress, downloadEndpointAddress)
    }
}
