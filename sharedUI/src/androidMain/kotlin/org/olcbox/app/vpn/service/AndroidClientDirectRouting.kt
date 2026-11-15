package org.olcbox.app.vpn.service

import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.ClientDirectRoutingConfig
import org.olcbox.app.vpn.XrayProxyConfig
import org.olcbox.app.vpn.XraySocksSettings

internal fun applyAndroidClientDirectRouting(config: String, enabled: Boolean, profile: VpnProfileConfig? = null): String =
    if (enabled) ClientDirectRoutingConfig.apply(config, profile = profile) else config

internal fun buildAndroidNativeProxyConfig(profile: VpnProfileConfig, socks: XraySocksSettings, directRouting: Boolean): String =
    applyAndroidClientDirectRouting(XrayProxyConfig.build(profile, socks,
        bootstrapDnsServers = listOf("1.1.1.1", "8.8.8.8")), directRouting, profile)

internal fun buildAndroidDirectSocksConfig(socks: XraySocksSettings, upstream: XraySocksSettings,
                                         profile: VpnProfileConfig? = null): String =
    ClientDirectRoutingConfig.buildSocksFront(socks, upstream, profile = profile)

internal fun androidTunBridgePreservesDns(profileType: String, directRouting: Boolean): Boolean =
    directRouting || VpnProfileConfig.normalizeType(profileType) in setOf(
        VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)
