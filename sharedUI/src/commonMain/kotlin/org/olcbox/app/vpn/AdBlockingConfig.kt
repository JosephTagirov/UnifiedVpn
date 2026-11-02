package org.olcbox.app.vpn

import kotlinx.serialization.json.*

/** Local filtering only. Every allowed connection and DNS lookup uses the existing tunnel. */
internal object AdBlockingConfig {
    const val INBOUND = "tun-bridge-in"
    const val UPSTREAM = "tun-upstream"
    const val RULES = "ads"

    fun build(
        listenHost: String, listenPort: Int, username: String, password: String,
        upstreamHost: String, upstreamPort: Int, rulesPath: String,
        upstreamUsername: String = username, upstreamPassword: String = password,
        preserveUpstreamDns: Boolean = false
    ): String {
        require(listenPort in 1..65535 && upstreamPort in 1..65535 && listenPort != upstreamPort)
        require(rulesPath.isNotBlank())
        val inbound = buildJsonObject {
            put("type", "socks"); put("tag", INBOUND)
            put("listen", listenHost); put("listen_port", listenPort)
            if (username.isNotBlank()) putJsonArray("users") { addJsonObject {
                put("username", username); put("password", password)
            } }
        }
        val outbound = buildJsonObject {
            put("type", "socks"); put("tag", UPSTREAM)
            put("server", upstreamHost); put("server_port", upstreamPort); put("version", "5")
            if (upstreamUsername.isNotBlank()) {
                put("username", upstreamUsername); put("password", upstreamPassword)
            }
        }
        return buildJsonObject {
            putJsonObject("log") { put("level", "error") }
            putJsonArray("inbounds") { add(inbound) }
            putJsonArray("outbounds") { add(outbound) }
            putJsonObject("dns") {
                putJsonArray("servers") { addJsonObject {
                    // Same encrypted resolver as the existing Android DNS bridge.
                    put("tag", "tun-dns"); put("detour", UPSTREAM)
                    if (preserveUpstreamDns) {
                        // The existing WireGuard SOCKS engine hijacks DNS and honors the profile resolver.
                        put("type", "tcp"); put("server", "1.1.1.1"); put("server_port", 53)
                    } else {
                        put("type", "https"); put("server", "8.8.8.8")
                        put("server_port", 443); put("path", "/dns-query")
                        putJsonObject("tls") { put("enabled", true); put("server_name", "dns.google") }
                    }
                } }
                putJsonArray("rules") { addJsonObject {
                    put("rule_set", RULES); put("action", "predefined"); put("rcode", "NXDOMAIN")
                } }
                put("final", "tun-dns"); put("strategy", if (preserveUpstreamDns) "prefer_ipv4" else "ipv4_only"); put("reverse_mapping", true)
            }
            putJsonObject("route") {
                putJsonArray("rule_set") { addJsonObject {
                    put("type", "local"); put("tag", RULES); put("format", "source"); put("path", rulesPath)
                } }
                putJsonArray("rules") {
                    addJsonObject { put("inbound", INBOUND); put("port", 53); put("action", "hijack-dns") }
                    addJsonObject { put("action", "sniff"); put("timeout", "300ms") }
                    addJsonObject { put("rule_set", RULES); put("action", "reject"); put("no_drop", true) }
                }
                put("final", UPSTREAM)
            }
        }.toString()
    }
}
