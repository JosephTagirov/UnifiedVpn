package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.XraySocksSettings
import org.olcbox.app.vpn.XrayProxyConfig

internal object DesktopXrayConfig {
    fun supports(profile: VpnProfileConfig) = XrayProxyConfig.supports(profile)
    fun endpointHost(profile: VpnProfileConfig) = XrayProxyConfig.endpointHost(profile)
    fun downloadEndpointHost(profile: VpnProfileConfig) = XrayProxyConfig.downloadEndpointHost(profile)
    fun endpointHosts(profile: VpnProfileConfig) = XrayProxyConfig.endpointHosts(profile)
    fun build(profile: VpnProfileConfig, socks: DesktopSocksProxySettings,
              endpointAddress: String? = null, downloadEndpointAddress: String? = null): String {
        val settings = socks.normalized()
        return XrayProxyConfig.build(profile, XraySocksSettings(settings.host, settings.port,
            settings.username, settings.password), endpointAddress, downloadEndpointAddress)
    }
}
