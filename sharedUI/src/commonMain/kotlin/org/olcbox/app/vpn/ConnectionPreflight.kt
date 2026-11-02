package org.olcbox.app.vpn

internal object ConnectionPreflight {
    const val VPN_ACTIVE = "Another VPN connection is active. Disconnect it before starting Unified VPN."
    const val PROXY_ACTIVE = "A system proxy is active. Disable it before starting Unified VPN."
    const val CHECK_FAILED = "Could not check active VPN and proxy connections. Try connecting again."
}
