package org.olcbox.app.vpn

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.model.XrayProfileConfig

/** Dispatches by real protocol; existing VLESS configuration stays unchanged. */
internal object XrayProxyConfig {
    fun supports(profile: VpnProfileConfig): Boolean = if (profile.normalizedType == VpnProfileConfig.TYPE_VLESS)
        XrayVlessConfig.supports(profile) else XrayProfileConfig.parse(profile) != null
    fun endpointHost(profile: VpnProfileConfig): String = if (profile.normalizedType == VpnProfileConfig.TYPE_VLESS)
        XrayVlessConfig.endpointHost(profile) else parsed(profile).host
    fun endpointHosts(profile: VpnProfileConfig): List<String> = if (profile.normalizedType == VpnProfileConfig.TYPE_VLESS)
        XrayVlessConfig.endpointHosts(profile) else listOf(parsed(profile).host)
    fun downloadEndpointHost(profile: VpnProfileConfig): String? = if (profile.normalizedType == VpnProfileConfig.TYPE_VLESS)
        XrayVlessConfig.downloadEndpointHost(profile) else null
    private fun parsed(profile: VpnProfileConfig) = XrayProfileConfig.parse(profile)
        ?: throw IllegalArgumentException("Invalid ${profile.typeLabel()} configuration")

    fun build(profile: VpnProfileConfig, socks: XraySocksSettings, endpointAddress: String? = null,
              downloadEndpointAddress: String? = null, bootstrapDnsServers: List<String> = emptyList()): String {
        if (profile.normalizedType == VpnProfileConfig.TYPE_VLESS)
            return XrayVlessConfig.build(profile,socks,endpointAddress,downloadEndpointAddress,bootstrapDnsServers)
        val p = parsed(profile)
        require(socks.port in 1..65535) { "Invalid SOCKS port" }
        val stream = buildJsonObject {
            put("network",p.transport)
            put("security",if(p.tls) "tls" else "none")
            if (p.tls) put("tlsSettings",buildJsonObject {
                put("serverName",p.sni);put("allowInsecure",false)
                if(p.alpn.isNotEmpty()) put("alpn",buildJsonArray { p.alpn.forEach { add(JsonPrimitive(it)) } })
                p.fingerprint?.let { put("fingerprint",it) }
            })
            when(p.transport) {
                "hysteria" -> put("hysteriaSettings",buildJsonObject { put("version",2);put("auth",p.credential) })
                "ws", "httpupgrade" -> put(if(p.transport=="ws") "wsSettings" else "httpupgradeSettings",buildJsonObject {
                    put("path",p.path);put("host",p.hostHeader ?: p.sni)
                })
                "grpc" -> put("grpcSettings",buildJsonObject {
                    put("serviceName",p.path.removePrefix("/"));put("multiMode",p.mode=="multi")
                    put("authority",p.hostHeader ?: p.sni)
                })
                "xhttp" -> put("xhttpSettings",buildJsonObject {
                    put("path",p.path);put("host",p.hostHeader ?: p.sni);p.mode?.let { put("mode",it) }
                })
            }
        }
        val outbound = buildJsonObject {
            put("tag","proxy")
            put("protocol",if(p.type==VpnProfileConfig.TYPE_HYSTERIA2) "hysteria" else "vmess")
            put("settings",buildJsonObject {
                if(p.type==VpnProfileConfig.TYPE_HYSTERIA2) {
                    put("version",2);put("address",endpointAddress ?: p.host);put("port",p.port)
                } else put("vnext",buildJsonArray { add(buildJsonObject {
                    put("address",endpointAddress ?: p.host);put("port",p.port)
                    put("users",buildJsonArray { add(buildJsonObject { put("id",p.credential);put("alterId",0);put("security",p.cipher) }) })
                }) })
            })
            put("streamSettings",stream)
        }
        return buildJsonObject {
            put("log",buildJsonObject { put("loglevel","warning");put("dnsLog",false) })
            put("inbounds",buildJsonArray { add(buildJsonObject {
                put("listen",socks.host);put("port",socks.port);put("protocol","socks")
                put("settings",buildJsonObject {
                    put("udp",true);put("auth",if(socks.username.isBlank()) "noauth" else "password")
                    if(socks.username.isNotBlank()) put("accounts",buildJsonArray { add(buildJsonObject {
                        put("user",socks.username);put("pass",socks.password)
                    }) })
                })
            }) })
            put("outbounds",buildJsonArray { add(outbound);add(buildJsonObject { put("protocol","freedom");put("tag","direct") }) })
            if(bootstrapDnsServers.isNotEmpty()) {
                put("dns",buildJsonObject { put("queryStrategy","UseIPv4");put("servers",buildJsonArray { bootstrapDnsServers.forEach { add(JsonPrimitive(it)) } }) })
                put("routing",buildJsonObject { put("domainStrategy","AsIs");put("rules",buildJsonArray { add(buildJsonObject {
                    put("type","field");put("ip",buildJsonArray { bootstrapDnsServers.forEach { add(JsonPrimitive(it)) } })
                    put("port","53");put("network","udp,tcp");put("outboundTag","direct")
                }) }) })
            }
        }.toString()
    }
}
