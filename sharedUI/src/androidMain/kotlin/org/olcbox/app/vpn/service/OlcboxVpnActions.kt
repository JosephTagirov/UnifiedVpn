package org.olcbox.app.vpn.service

object OlcboxVpnActions {
    const val SERVICE_CLASS_NAME = "org.olcbox.app.vpn.service.OlcboxVpnService"
    const val ACTION_OPEN_PROFILE_CHOOSER =
        "org.olcbox.app.vpn.service.OlcboxVpnService.OPEN_PROFILE_CHOOSER"
    const val ACTION_START_VPN = "org.olcbox.app.vpn.service.OlcboxVpnService.START"
    const val ACTION_STOP_VPN = "org.olcbox.app.vpn.service.OlcboxVpnService.STOP"
    const val ACTION_APPLY_SELECTED_PROFILE =
        "org.olcbox.app.vpn.service.OlcboxVpnService.APPLY_SELECTED_PROFILE"
    const val ACTION_RETRY_PROFILE =
        "org.olcbox.app.vpn.service.OlcboxVpnService.RETRY_PROFILE"
    const val ACTION_SWITCH_PREVIOUS_PROFILE =
        "org.olcbox.app.vpn.service.OlcboxVpnService.SWITCH_PREVIOUS_PROFILE"
    const val ACTION_SWITCH_NEXT_PROFILE =
        "org.olcbox.app.vpn.service.OlcboxVpnService.SWITCH_NEXT_PROFILE"
    const val EXTRA_PROFILE_STORAGE_ID =
        "org.olcbox.app.vpn.service.OlcboxVpnService.PROFILE_STORAGE_ID"
    const val EXTRA_CONNECTION_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.CONNECTION_MODE"
    const val EXTRA_SOCKS_HOST = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_HOST"
    const val EXTRA_SOCKS_PORT = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_PORT"
    const val EXTRA_SOCKS_USERNAME = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_USERNAME"
    const val EXTRA_SOCKS_PASSWORD = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_PASSWORD"
    const val EXTRA_SPLIT_TUNNEL_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_MODE"
    const val EXTRA_SPLIT_TUNNEL_PROXY_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_PROXY_APPS"
    const val EXTRA_SPLIT_TUNNEL_BYPASS_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_BYPASS_APPS"
    const val EXTRA_SPLIT_TUNNEL_OLCRTC_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_OLCRTC_MODE"
    const val EXTRA_SPLIT_TUNNEL_OLCRTC_PROXY_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_OLCRTC_PROXY_APPS"
    const val EXTRA_SPLIT_TUNNEL_OLCRTC_BYPASS_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_OLCRTC_BYPASS_APPS"
    const val EXTRA_SPLIT_TUNNEL_EXTERNAL_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_EXTERNAL_MODE"
    const val EXTRA_SPLIT_TUNNEL_EXTERNAL_PROXY_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_EXTERNAL_PROXY_APPS"
    const val EXTRA_SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_EXTERNAL_BYPASS_APPS"
}
