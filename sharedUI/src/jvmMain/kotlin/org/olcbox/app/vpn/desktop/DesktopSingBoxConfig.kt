package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

internal object DesktopSingBoxConfig {
    private val json = Json { prettyPrint = true }

    fun build(profile: VpnProfileConfig, socks: DesktopSocksProxySettings): String {
        val normalizedProfile = profile.normalized()
        require(
            normalizedProfile.normalizedType == VpnProfileConfig.TYPE_AMNEZIA_WG ||
                normalizedProfile.normalizedType == VpnProfileConfig.TYPE_AMNEZIA_VPN
        ) { "Unsupported sing-box profile type: ${normalizedProfile.normalizedType}" }

        val outbound = parseWireGuardConfig(resolveWireGuardConfig(normalizedProfile))
        val settings = socks.normalized()
        val inbound = buildJsonObject {
            put("type", "socks")
            put("tag", LOCAL_SOCKS_TAG)
            put("listen", settings.host)
            put("listen_port", settings.port)
            if (settings.username.isNotBlank()) {
                put(
                    "users",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("username", settings.username)
                                put("password", settings.password)
                            }
                        )
                    }
                )
            }
        }
        val peer = buildJsonObject {
            put("address", outbound.peerHost)
            put("port", outbound.peerPort)
            put("public_key", outbound.peerPublicKey)
            put("allowed_ips", JsonArray(outbound.allowedIps.map(::JsonPrimitive)))
            outbound.preSharedKey?.let { put("pre_shared_key", it) }
            outbound.persistentKeepalive?.let { put("persistent_keepalive_interval", it) }
        }
        val endpoint = buildJsonObject {
            put("type", "wireguard")
            put("tag", AMNEZIA_WIREGUARD_TAG)
            put("address", JsonArray(outbound.addresses.map(::JsonPrimitive)))
            put("private_key", outbound.privateKey)
            put("peers", buildJsonArray { add(peer) })
            outbound.mtu?.let { put("mtu", it) }
            if (outbound.amneziaWireGuardFields.isNotEmpty()) {
                put("amnezia_wg", JsonObject(outbound.amneziaWireGuardFields))
            }
        }
        val root = buildJsonObject {
            put("log", buildJsonObject { put("level", "warn") })
            put("inbounds", buildJsonArray { add(inbound) })
            put("endpoints", buildJsonArray { add(endpoint) })
            put(
                "outbounds",
                buildJsonArray {
                    add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                }
            )
            put(
                "route",
                buildJsonObject {
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("inbound", LOCAL_SOCKS_TAG)
                                    put("action", "sniff")
                                }
                            )
                        }
                    )
                    put("final", AMNEZIA_WIREGUARD_TAG)
                }
            )
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    fun endpointHost(profile: VpnProfileConfig): String {
        return parseWireGuardConfig(resolveWireGuardConfig(profile.normalized())).peerHost
    }

    private fun resolveWireGuardConfig(profile: VpnProfileConfig): String {
        listOfNotNull(profile.rawConfig, profile.uri)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { candidate ->
                extractWireGuardConfig(candidate)?.let { return it }
            }
        error("${profile.typeLabel()} profile does not contain a WireGuard config")
    }

    private fun extractWireGuardConfig(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (looksLikeWireGuardConfig(trimmed)) return trimmed

        val decoded = when {
            trimmed.startsWith("vpn://", ignoreCase = true) -> decodeCompressedVpnUri(trimmed, "vpn://")
            trimmed.startsWith("awg://", ignoreCase = true) -> decodeCompressedVpnUri(trimmed, "awg://")
            else -> null
        }
        if (!decoded.isNullOrBlank()) {
            extractWireGuardConfig(decoded)?.let { return it }
        }

        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return extractWireGuardConfig(element)
    }

    private fun extractWireGuardConfig(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                element["config"]
                ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    ?.takeIf(::looksLikeWireGuardConfig)
                    ?: element.values.firstNotNullOfOrNull(::extractWireGuardConfig)
            }
            is JsonArray -> element.firstNotNullOfOrNull(::extractWireGuardConfig)
            is JsonPrimitive -> element.contentOrNull?.let(::extractWireGuardConfig)
        }
    }

    private fun decodeCompressedVpnUri(uri: String, prefix: String): String? {
        val encoded = uri.substring(prefix.length).substringBefore('#').trim()
        if (encoded.isBlank()) return null

        return runCatching {
            val compressed = Base64.getUrlDecoder().decode(encoded)
            val payload = if (compressed.size > 4) compressed.copyOfRange(4, compressed.size) else compressed
            val inflater = Inflater()
            val output = ByteArrayOutputStream()
            try {
                inflater.setInput(payload)
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        require(output.size() + count <= MAX_DECOMPRESSED_PROFILE_SIZE) {
                            "Compressed VPN profile is too large"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } finally {
                inflater.end()
            }
            output.toString(Charsets.UTF_8.name())
        }.getOrNull()
    }

    private fun parseWireGuardConfig(config: String): WireGuardOutbound {
        val parsed = parseWireGuardIni(config)
        val peer = parsed.peers.firstOrNull()
            ?: throw IllegalArgumentException("WireGuard peer section is missing")
        val endpoint = parseEndpoint(
            peer["endpoint"] ?: throw IllegalArgumentException("WireGuard peer endpoint is missing")
        )
        val awgFields = AMNEZIA_WG_FIELDS.mapNotNull { (configKey, singBoxKey) ->
            parsed.allValues[configKey]
                ?.takeIf { it.isAwgParameterEnabled() }
                ?.let {
                    val normalized = it.trim().trim('"', '\'')
                    val value = if (singBoxKey in AMNEZIA_WG_INTEGER_FIELDS) {
                        normalized.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(normalized)
                    } else {
                        JsonPrimitive(normalized)
                    }
                    singBoxKey to value
                }
        }.toMap()

        return WireGuardOutbound(
            privateKey = parsed.interfaceConfig["privatekey"]
                ?: throw IllegalArgumentException("WireGuard private key is missing"),
            addresses = parsed.interfaceConfig["address"].orEmpty().splitCsv(),
            mtu = parsed.interfaceConfig["mtu"]?.toIntOrNull(),
            peerPublicKey = peer["publickey"]
                ?: throw IllegalArgumentException("WireGuard peer public key is missing"),
            preSharedKey = peer["presharedkey"]?.takeIf(String::isNotBlank),
            peerHost = endpoint.host,
            peerPort = endpoint.port,
            allowedIps = peer["allowedips"].orEmpty().splitCsv().ifEmpty { listOf("0.0.0.0/0") },
            persistentKeepalive = peer["persistentkeepalive"]?.toIntOrNull(),
            amneziaWireGuardFields = awgFields
        ).also {
            require(it.addresses.isNotEmpty()) { "WireGuard interface address is missing" }
        }
    }

    private fun parseWireGuardIni(config: String): ParsedWireGuardConfig {
        val interfaceConfig = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        val allValues = linkedMapOf<String, String>()
        var section: String? = null
        var values: MutableMap<String, String>? = null

        config.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isBlank()) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.removePrefix("[").removeSuffix("]").trim().lowercase()
                values = when (section) {
                    "interface" -> interfaceConfig
                    "peer" -> linkedMapOf<String, String>().also(peers::add)
                    else -> null
                }
                return@forEach
            }
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            values?.put(key, value)
            if (section == "interface" || section == "peer") allValues[key] = value
        }
        return ParsedWireGuardConfig(interfaceConfig, peers, allValues)
    }

    private fun parseEndpoint(endpoint: String): HostPort {
        val value = endpoint.trim()
        require(value.isNotBlank()) { "WireGuard endpoint is blank" }
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val closing = value.indexOf(']')
            require(closing > 0 && value.length > closing + 2 && value[closing + 1] == ':') {
                "Invalid WireGuard IPv6 endpoint"
            }
            host = value.substring(1, closing)
            portText = value.substring(closing + 2)
        } else {
            val separator = value.lastIndexOf(':')
            require(separator > 0) { "Invalid WireGuard endpoint" }
            host = value.substring(0, separator)
            portText = value.substring(separator + 1)
        }
        val port = portText.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid WireGuard endpoint port")
        require(port in 1..65535) { "WireGuard endpoint port is out of range" }
        return HostPort(host, port)
    }

    private fun looksLikeWireGuardConfig(config: String): Boolean {
        val lower = config.lowercase()
        return lower.contains("[interface]") &&
            lower.contains("[peer]") &&
            lower.contains("privatekey") &&
            lower.contains("publickey")
    }

    private fun String.splitCsv(): List<String> = split(',').map(String::trim).filter(String::isNotBlank)

    private fun String.isAwgParameterEnabled(): Boolean {
        val normalized = trim().trim('"', '\'').lowercase()
        return normalized.isNotBlank() &&
            normalized != "0" &&
            normalized != "0-0" &&
            normalized != "false" &&
            normalized != "off"
    }

    private data class ParsedWireGuardConfig(
        val interfaceConfig: Map<String, String>,
        val peers: List<Map<String, String>>,
        val allValues: Map<String, String>
    )

    private data class WireGuardOutbound(
        val privateKey: String,
        val addresses: List<String>,
        val mtu: Int?,
        val peerPublicKey: String,
        val preSharedKey: String?,
        val peerHost: String,
        val peerPort: Int,
        val allowedIps: List<String>,
        val persistentKeepalive: Int?,
        val amneziaWireGuardFields: Map<String, JsonPrimitive>
    )

    private data class HostPort(val host: String, val port: Int)

    private const val LOCAL_SOCKS_TAG = "local-socks"
    private const val AMNEZIA_WIREGUARD_TAG = "amnezia-wireguard"
    private const val MAX_DECOMPRESSED_PROFILE_SIZE = 4 * 1024 * 1024
    private val AMNEZIA_WG_INTEGER_FIELDS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4"
    )
    private val AMNEZIA_WG_FIELDS = linkedMapOf(
        "jc" to "jc",
        "jmin" to "jmin",
        "jmax" to "jmax",
        "s1" to "s1",
        "s2" to "s2",
        "s3" to "s3",
        "s4" to "s4",
        "h1" to "h1",
        "h2" to "h2",
        "h3" to "h3",
        "h4" to "h4",
        "i1" to "i1",
        "i2" to "i2",
        "i3" to "i3",
        "i4" to "i4",
        "i5" to "i5",
        "headerprotectionkey" to "header_protection_key",
        "contentpaddingaddition" to "content_padding_addition",
        "rekeyaftertime" to "rekey_after_time",
        "rekeytimeout" to "rekey_timeout",
        "rejectaftertime" to "reject_after_time",
        "keepalivetimeout" to "keepalive_timeout",
        "maxhandshakeattempts" to "max_handshake_attempts"
    )
}
