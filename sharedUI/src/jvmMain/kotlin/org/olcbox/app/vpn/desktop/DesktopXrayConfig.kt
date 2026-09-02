package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.net.URLDecoder

internal object DesktopXrayConfig {
    private val json = Json { prettyPrint = true }

    fun supports(profile: VpnProfileConfig): Boolean {
        return parse(profile).transport in XRAY_TRANSPORTS
    }

    fun endpointHost(profile: VpnProfileConfig): String = parse(profile).host

    fun build(profile: VpnProfileConfig, socks: DesktopSocksProxySettings): String {
        val outbound = parse(profile)
        require(outbound.transport in XRAY_TRANSPORTS) {
            "Xray is only required for VLESS XHTTP profiles"
        }
        val settings = socks.normalized()
        val inbound = buildJsonObject {
            put("listen", settings.host)
            put("port", settings.port)
            put("protocol", "socks")
            put(
                "settings",
                buildJsonObject {
                    put("udp", true)
                    if (settings.username.isBlank()) {
                        put("auth", "noauth")
                    } else {
                        put("auth", "password")
                        put(
                            "accounts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("user", settings.username)
                                        put("pass", settings.password)
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
        val user = buildJsonObject {
            put("id", outbound.uuid)
            put("encryption", outbound.encryption ?: "none")
            outbound.flow?.let { put("flow", it) }
        }
        val proxy = buildJsonObject {
            put("protocol", "vless")
            put("tag", PROXY_TAG)
            put(
                "settings",
                buildJsonObject {
                    put(
                        "vnext",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("address", outbound.host)
                                    put("port", outbound.port)
                                    put("users", buildJsonArray { add(user) })
                                }
                            )
                        }
                    )
                }
            )
            put("streamSettings", buildStreamSettings(outbound))
        }
        val root = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("loglevel", "warning")
                    put("dnsLog", false)
                }
            )
            put("inbounds", buildJsonArray { add(inbound) })
            put(
                "outbounds",
                buildJsonArray {
                    add(proxy)
                    add(
                        buildJsonObject {
                            put("protocol", "freedom")
                            put("tag", "direct")
                        }
                    )
                }
            )
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun buildStreamSettings(outbound: VlessOutbound) = buildJsonObject {
        put("network", "xhttp")
        put("security", outbound.security ?: "none")
        put(
            "xhttpSettings",
            buildJsonObject {
                put("path", outbound.path ?: "/")
                outbound.hostHeader?.let { put("host", it) }
                outbound.mode?.let { put("mode", it) }
            }
        )

        when (outbound.security) {
            "reality" -> put(
                "realitySettings",
                buildJsonObject {
                    put("show", false)
                    outbound.sni?.let { put("serverName", it) }
                    outbound.fingerprint?.let { put("fingerprint", it) }
                    outbound.publicKey?.let { put("publicKey", it) }
                    outbound.shortId?.let { put("shortId", it) }
                    outbound.spiderX?.let { put("spiderX", it) }
                }
            )
            "tls" -> put(
                "tlsSettings",
                buildJsonObject {
                    outbound.sni?.let { put("serverName", it) }
                    outbound.fingerprint?.let { put("fingerprint", it) }
                    outbound.alpn?.takeIf(List<String>::isNotEmpty)?.let { values ->
                        put("alpn", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                    outbound.allowInsecure?.let { put("allowInsecure", it) }
                }
            )
        }
    }

    private fun parse(profile: VpnProfileConfig): VlessOutbound {
        val normalized = profile.normalized()
        require(normalized.normalizedType == VpnProfileConfig.TYPE_VLESS) {
            "Unsupported Xray profile type: ${normalized.normalizedType}"
        }
        val uri = normalized.rawConfig?.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?: normalized.uri?.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?: error("VLESS profile does not contain a vless:// URI")
        return parseVlessUri(uri)
    }

    private fun parseVlessUri(uri: String): VlessOutbound {
        val payload = uri.substringAfter("vless://", "")
        require(payload.isNotBlank()) { "Invalid VLESS URI" }
        val withoutFragment = payload.substringBefore('#')
        val authority = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
        val at = authority.lastIndexOf('@')
        require(at > 0) { "Invalid VLESS URI authority" }
        val uuid = authority.substring(0, at).urlDecode()
        val hostPort = authority.substring(at + 1)
        val endpoint = parseHostPort(hostPort)
        val params = parseQuery(query)

        return VlessOutbound(
            uuid = uuid,
            host = endpoint.host,
            port = endpoint.port,
            encryption = params["encryption"],
            flow = params["flow"]?.takeIf(String::isNotBlank),
            security = params["security"]?.lowercase()?.takeIf(String::isNotBlank),
            sni = params["sni"] ?: params["serverName"],
            fingerprint = params["fp"] ?: params["fingerprint"],
            publicKey = params["pbk"] ?: params["publicKey"],
            shortId = params["sid"] ?: params["shortId"],
            spiderX = params["spx"] ?: params["spiderX"],
            transport = params["type"]?.lowercase(),
            path = params["path"],
            hostHeader = params["host"],
            mode = params["mode"],
            alpn = params["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotBlank),
            allowInsecure = params["allowInsecure"]?.toBooleanStrictOrNull()
        )
    }

    private fun parseHostPort(value: String): HostPort {
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val closing = value.indexOf(']')
            require(closing > 0 && value.getOrNull(closing + 1) == ':') {
                "Invalid VLESS IPv6 endpoint"
            }
            host = value.substring(1, closing).urlDecode()
            portText = value.substring(closing + 2)
        } else {
            val separator = value.lastIndexOf(':')
            require(separator > 0) { "Invalid VLESS endpoint" }
            host = value.substring(0, separator).urlDecode()
            portText = value.substring(separator + 1)
        }
        val port = portText.toIntOrNull() ?: error("Invalid VLESS endpoint port")
        require(port in 1..65535) { "VLESS endpoint port is out of range" }
        return HostPort(host, port)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            part.substring(0, separator).urlDecode() to
                part.substring(separator + 1).urlDecode()
        }.toMap()
    }

    private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8)

    private data class HostPort(val host: String, val port: Int)

    private data class VlessOutbound(
        val uuid: String,
        val host: String,
        val port: Int,
        val encryption: String?,
        val flow: String?,
        val security: String?,
        val sni: String?,
        val fingerprint: String?,
        val publicKey: String?,
        val shortId: String?,
        val spiderX: String?,
        val transport: String?,
        val path: String?,
        val hostHeader: String?,
        val mode: String?,
        val alpn: List<String>?,
        val allowInsecure: Boolean?
    )

    private const val PROXY_TAG = "proxy"
    private val XRAY_TRANSPORTS = setOf("xhttp", "splithttp")
}
