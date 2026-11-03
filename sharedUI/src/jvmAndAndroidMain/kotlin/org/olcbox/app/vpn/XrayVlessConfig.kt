package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.data.model.VpnProfileConfig
import java.net.URLDecoder

internal object XrayVlessConfig {
    private val json = Json { prettyPrint = true }

    fun supports(profile: VpnProfileConfig): Boolean {
        return parse(profile).transport in XRAY_TRANSPORTS
    }

    fun endpointHost(profile: VpnProfileConfig): String = parse(profile).host

    fun downloadEndpointHost(profile: VpnProfileConfig): String? = downloadHost(parse(profile).extra)

    fun endpointHosts(profile: VpnProfileConfig): List<String> = parse(profile).let {
        listOfNotNull(it.host, downloadHost(it.extra)).distinct()
    }

    fun build(
        profile: VpnProfileConfig,
        socks: XraySocksSettings,
        endpointAddress: String? = null,
        downloadEndpointAddress: String? = null,
        bootstrapDnsServers: List<String> = emptyList()
    ): String {
        val outbound = parse(profile)
        require(outbound.transport in XRAY_TRANSPORTS) {
            "Unsupported VLESS transport: ${outbound.transport}. " +
                "Use tcp/raw, ws, grpc, httpupgrade or xhttp."
        }
        require(outbound.transport != "raw" || outbound.headerType in setOf(null, "none")) {
            "VLESS TCP header obfuscation is not supported"
        }
        require(outbound.transport != "grpc" || outbound.mode in setOf(null, "gun", "multi")) {
            "Unsupported VLESS gRPC mode; use gun or multi"
        }
        require(outbound.security != "reality" || outbound.transport in setOf("raw", "grpc", "xhttp")) {
            "VLESS REALITY requires tcp/raw, grpc or xhttp transport"
        }
        val settings = socks
        require(settings.port in 1..65535) { "Invalid SOCKS port" }
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
                                    put("address", endpointAddress ?: outbound.host)
                                    put("port", outbound.port)
                                    put("users", buildJsonArray { add(user) })
                                }
                            )
                        }
                    )
                }
            )
            val stream = buildStreamSettings(outbound, endpointAddress != null, downloadEndpointAddress)
            put("streamSettings", if (bootstrapDnsServers.isEmpty()) stream else JsonObject(stream +
                ("sockopt" to buildJsonObject { put("domainStrategy", "UseIPv4") })))
        }
        val root = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("loglevel", "warning")
                    put("dnsLog", false)
                }
            )
            if (bootstrapDnsServers.isNotEmpty()) {
                put("dns", buildJsonObject {
                    put("servers", buildJsonArray { bootstrapDnsServers.forEach { add(JsonPrimitive(it)) } })
                    put("queryStrategy", "UseIPv4")
                })
                put("routing", buildJsonObject {
                    put("domainStrategy", "AsIs")
                    put("rules", buildJsonArray { add(buildJsonObject {
                        put("type", "field")
                        put("ip", buildJsonArray { bootstrapDnsServers.forEach { add(JsonPrimitive(it)) } })
                        put("port", "53")
                        put("network", "udp,tcp")
                        put("outboundTag", "direct")
                    }) })
                })
            }
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

    private fun buildStreamSettings(
        outbound: VlessOutbound,
        pinnedEndpoint: Boolean,
        downloadEndpointAddress: String?
    ) = buildJsonObject {
        put("network", outbound.transport)
        put("security", outbound.security ?: "none")
        // Xray otherwise derives HTTP authority from SNI or the endpoint. Keep it when TUN pins an IP.
        val pinnedHttpHost = if (pinnedEndpoint) {
            outbound.sni?.takeIf { outbound.security in setOf("tls", "reality") } ?: outbound.host
        } else null
        when (outbound.transport) {
            "xhttp" -> put(
                "xhttpSettings",
                buildJsonObject {
                    put("path", outbound.path ?: "/")
                    (outbound.hostHeader ?: pinnedHttpHost)?.let { put("host", it) }
                    outbound.mode?.let { put("mode", it) }
                    pinDownloadSettings(outbound.extra, downloadEndpointAddress)?.let { put("extra", it) }
                }
            )
            "ws", "httpupgrade" -> put(
                if (outbound.transport == "ws") "wsSettings" else "httpupgradeSettings",
                buildJsonObject {
                    put("path", outbound.path ?: "/")
                    (outbound.hostHeader ?: pinnedHttpHost)?.let { put("host", it) }
                }
            )
            "grpc" -> put(
                "grpcSettings",
                buildJsonObject {
                    put("serviceName", outbound.serviceName ?: "")
                    put("multiMode", outbound.mode == "multi")
                    val authority = outbound.authority ?: outbound.hostHeader ?:
                        defaultGrpcAuthority(outbound).takeIf { pinnedEndpoint }
                    authority?.let { put("authority", it) }
                }
            )
        }

        when (outbound.security) {
            "reality" -> put(
                "realitySettings",
                buildJsonObject {
                    put("show", false)
                    (outbound.sni ?: outbound.host.takeIf { pinnedEndpoint })?.let { put("serverName", it) }
                    outbound.fingerprint?.let { put("fingerprint", it) }
                    outbound.publicKey?.let { put("publicKey", it) }
                    outbound.shortId?.let { put("shortId", it) }
                    outbound.spiderX?.let { put("spiderX", it) }
                }
            )
            "tls" -> put(
                "tlsSettings",
                buildJsonObject {
                    (outbound.sni ?: outbound.host.takeIf { pinnedEndpoint })?.let { put("serverName", it) }
                    outbound.fingerprint?.let { put("fingerprint", it) }
                    outbound.alpn?.takeIf(List<String>::isNotEmpty)?.let { values ->
                        put("alpn", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                    outbound.allowInsecure?.let { put("allowInsecure", it) }
                }
            )
        }
    }

    private fun downloadHost(extra: JsonObject?): String? =
        ((extra?.get("downloadSettings") as? JsonObject)?.get("address") as? JsonPrimitive)
            ?.contentOrNull?.takeIf(String::isNotBlank)

    private fun pinDownloadSettings(extra: JsonObject?, address: String?): JsonObject? {
        if (address == null) return extra
        val originalHost = downloadHost(extra) ?: error("Missing XHTTP download endpoint")
        val download = (extra?.get("downloadSettings") as JsonObject).toMutableMap()
        download["address"] = JsonPrimitive(address)
        val security = (download["security"] as? JsonPrimitive)?.contentOrNull
        val tlsKey = when (security) {
            "tls" -> "tlsSettings"
            "reality" -> "realitySettings"
            else -> null
        }
        var httpHost = originalHost
        if (tlsKey != null) {
            val tls = (download[tlsKey] as? JsonObject).orEmpty().toMutableMap()
            httpHost = (tls["serverName"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: originalHost
            tls["serverName"] = JsonPrimitive(httpHost)
            download[tlsKey] = JsonObject(tls)
        }
        val http = (download["xhttpSettings"] as? JsonObject).orEmpty().toMutableMap()
        if ((http["host"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) {
            http["host"] = JsonPrimitive(httpHost)
        }
        download["xhttpSettings"] = JsonObject(http)
        return JsonObject(extra.orEmpty() + ("downloadSettings" to JsonObject(download)))
    }

    private fun defaultGrpcAuthority(outbound: VlessOutbound): String {
        if (outbound.security == "tls" && outbound.sni != null) return outbound.sni
        val octets = outbound.host.split('.')
        val isIp = ':' in outbound.host || (octets.size == 4 && octets.all {
            it.toIntOrNull()?.let { number -> number in 0..255 } == true
        })
        // Without explicit TLS SNI, gRPC derives REALITY/IP authorities from the full dial target.
        if (outbound.security != "reality" && !isIp) return outbound.host
        val host = if (':' in outbound.host) "[${outbound.host}]" else outbound.host
        return "$host:${outbound.port}"
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
        val payload = vlessUriPayload(uri)
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
        val transport = when (val value = params["type"]?.lowercase()?.takeIf(String::isNotBlank)) {
            null, "tcp" -> "raw"
            "websocket" -> "ws"
            "splithttp" -> "xhttp"
            else -> value
        }

        return VlessOutbound(
            uuid = uuid,
            host = endpoint.host,
            port = endpoint.port,
            encryption = params["encryption"]?.takeIf(String::isNotBlank),
            flow = params["flow"]?.takeIf(String::isNotBlank),
            security = params["security"]?.lowercase()?.takeIf(String::isNotBlank),
            sni = (params["sni"] ?: params["serverName"])?.takeIf(String::isNotBlank),
            fingerprint = params["fp"] ?: params["fingerprint"],
            publicKey = params["pbk"] ?: params["publicKey"],
            shortId = params["sid"] ?: params["shortId"],
            spiderX = params["spx"] ?: params["spiderX"],
            transport = transport,
            extra = if (transport == "xhttp") parseXhttpExtra(params["extra"]) else null,
            path = params["path"],
            hostHeader = params["host"]?.takeIf(String::isNotBlank),
            mode = params["mode"]?.lowercase()?.takeIf(String::isNotBlank),
            headerType = params["headerType"]?.lowercase()?.takeIf(String::isNotBlank),
            serviceName = params["serviceName"] ?: params["service_name"],
            authority = params["authority"]?.takeIf(String::isNotBlank),
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
        val transport: String,
        val extra: JsonObject?,
        val path: String?,
        val hostHeader: String?,
        val mode: String?,
        val headerType: String?,
        val serviceName: String?,
        val authority: String?,
        val alpn: List<String>?,
        val allowInsecure: Boolean?
    )

    private const val PROXY_TAG = "proxy"
    private val XRAY_TRANSPORTS = setOf("raw", "ws", "grpc", "httpupgrade", "xhttp")
}

internal data class XraySocksSettings(
    val host: String = "127.0.0.1",
    val port: Int,
    val username: String = "",
    val password: String = ""
)
