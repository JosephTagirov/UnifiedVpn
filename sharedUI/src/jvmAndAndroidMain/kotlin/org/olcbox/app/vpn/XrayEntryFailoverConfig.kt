package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnProfileConfig

/** A stable authenticated SOCKS front. Entry health and priority belong to the session policy. */
internal object XrayEntryFailoverConfig {
    const val PRIMARY_TAG = "entry-primary"
    const val BACKUP_TAG = "entry-backup"
    const val BLOCK_TAG = "entry-block"
    const val BALANCER_TAG = "entry-auto"
    private const val MAIN_TAG = "entry-main"
    private const val PRIMARY_PROBE_TAG = "entry-probe-primary"
    private const val BACKUP_PROBE_TAG = "entry-probe-backup"
    private const val BOOTSTRAP_TAG = "entry-bootstrap"
    private const val BOOTSTRAP_OUTBOUND_TAG = "entry-bootstrap-dns"
    private val bootstrapResolvers = listOf("1.1.1.1", "8.8.8.8")
    private val json = Json { prettyPrint = true }

    fun buildVless(
        profile: VpnProfileConfig,
        mainSocks: XraySocksSettings,
        primaryEndpoint: VpnEntryEndpoint,
        backupEndpoint: VpnEntryEndpoint,
        primaryProbeSocks: XraySocksSettings,
        backupProbeSocks: XraySocksSettings,
        apiSocketPath: String
    ): String {
        val primary = primaryEndpoint.normalizedOrNull() ?: error("Invalid primary entry endpoint")
        val backup = backupEndpoint.normalizedOrNull() ?: error("Invalid backup entry endpoint")
        require(primary.host != backup.host || primary.port != backup.port) { "Entry endpoints must differ" }
        require(XrayVlessConfig.downloadEndpointHost(profile) == null) {
            "Entry failover cannot replace a separate XHTTP download endpoint"
        }
        validatePorts(mainSocks, primaryProbeSocks, backupProbeSocks)
        val cleanProfile = profile.copy(entryFailover = null)
        val bootstrapDomains = listOf(primary.host, backup.host).filterNot(::isIpLiteral).distinct()
        fun outbound(endpoint: VpnEntryEndpoint, tag: String): JsonObject {
            val base = Json.parseToJsonElement(XrayVlessConfig.build(cleanProfile, mainSocks,
                endpointAddress = endpoint.host)).jsonObject
            val original = base.getValue("outbounds").jsonArray.first().jsonObject
            val stream = original.getValue("streamSettings").jsonObject
            require(stream["security"] in setOf(JsonPrimitive("tls"), JsonPrimitive("reality"))) {
                "Entry failover requires verified transport security"
            }
            require(stream["tlsSettings"]?.jsonObject?.get("allowInsecure") != JsonPrimitive(true)) {
                "Entry failover requires certificate verification"
            }
            val settings = original.getValue("settings").jsonObject
            val peers = settings.getValue("vnext").jsonArray
            require(peers.size == 1) { "Entry failover requires one VLESS credential" }
            val peer = JsonObject(peers.single().jsonObject + ("port" to JsonPrimitive(endpoint.port)))
            val pinnedSettings = JsonObject(settings + ("vnext" to JsonArray(listOf(peer))))
            val entryStream = if (isIpLiteral(endpoint.host)) stream else JsonObject(stream +
                ("sockopt" to buildJsonObject { put("domainStrategy", "UseIPv4") }))
            return JsonObject(original + mapOf(
                "tag" to JsonPrimitive(tag),
                "settings" to pinnedSettings,
                "streamSettings" to entryStream))
        }
        return render(mainSocks, listOf(
            inbound(primaryProbeSocks, PRIMARY_PROBE_TAG), inbound(backupProbeSocks, BACKUP_PROBE_TAG)
        ), listOf(outbound(primary, PRIMARY_TAG), outbound(backup, BACKUP_TAG)), apiSocketPath, bootstrapDomains)
    }

    fun buildSocks(
        mainSocks: XraySocksSettings,
        primaryBackendSocks: XraySocksSettings,
        backupBackendSocks: XraySocksSettings,
        apiSocketPath: String
    ): String {
        validatePorts(mainSocks, primaryBackendSocks, backupBackendSocks)
        fun outbound(settings: XraySocksSettings, tag: String) = buildJsonObject {
            put("tag", tag)
            put("protocol", "socks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray { add(buildJsonObject {
                    put("address", settings.host)
                    put("port", settings.port)
                    put("users", buildJsonArray { add(buildJsonObject {
                        put("user", settings.username)
                        put("pass", settings.password)
                    }) })
                }) })
            })
        }
        return render(mainSocks, emptyList(), listOf(outbound(primaryBackendSocks, PRIMARY_TAG),
            outbound(backupBackendSocks, BACKUP_TAG)), apiSocketPath)
    }

    private fun validatePorts(vararg endpoints: XraySocksSettings) {
        require(endpoints.map { it.port }.distinct().size == endpoints.size) { "Entry SOCKS ports must differ" }
        for (endpoint in endpoints) {
            require(endpoint.host in setOf("127.0.0.1", "::1")) { "Entry SOCKS must listen on loopback" }
            require(endpoint.port in 1..65535) { "Invalid entry SOCKS port" }
            require(endpoint.username.isNotBlank() && endpoint.password.isNotBlank()) {
                "Entry SOCKS authentication is required"
            }
        }
    }

    private fun isIpLiteral(host: String): Boolean = ':' in host || host.split('.').let { octets ->
        octets.size == 4 && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun inbound(settings: XraySocksSettings, tag: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("listen", settings.host)
        put("port", settings.port)
        put("protocol", "socks")
        put("settings", buildJsonObject {
            put("udp", true)
            put("auth", "password")
            put("accounts", buildJsonArray { add(buildJsonObject {
                put("user", settings.username)
                put("pass", settings.password)
            }) })
        })
    }

    private fun render(
        mainSocks: XraySocksSettings,
        probes: List<JsonObject>,
        entries: List<JsonObject>,
        apiSocketPath: String,
        bootstrapDomains: List<String> = emptyList()
    ): String {
        require(apiSocketPath.startsWith('/') && !apiSocketPath.endsWith('/') &&
            apiSocketPath.encodeToByteArray().size <= 100 &&
            apiSocketPath.split('/').none { it == "." || it == ".." } &&
            apiSocketPath.none { it.code < 32 || it == ',' || it == '\\' }) {
            "Entry control requires a short absolute private filesystem socket"
        }
        fun rule(inboundTag: String, outboundTag: String? = null) = buildJsonObject {
            put("type", "field")
            put("inboundTag", buildJsonArray { add(JsonPrimitive(inboundTag)) })
            put("network", "tcp,udp")
            if (outboundTag != null) put("outboundTag", outboundTag) else put("balancerTag", BALANCER_TAG)
        }
        val root = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning"); put("dnsLog", false) })
            put("api", buildJsonObject {
                put("tag", "entry-control")
                put("listen", "$apiSocketPath,0600")
                put("services", buildJsonArray { add(JsonPrimitive("RoutingService")) })
            })
            // RoutingService itself requires the stats feature, not the StatsService API.
            put("stats", buildJsonObject { })
            if (bootstrapDomains.isNotEmpty()) put("dns", buildJsonObject {
                // Only the core's entry hostname lookups carry this private, non-listening inbound tag.
                put("tag", BOOTSTRAP_TAG)
                put("queryStrategy", "UseIPv4")
                put("disableFallback", true)
                put("servers", buildJsonArray { bootstrapResolvers.forEach { resolver -> add(buildJsonObject {
                    put("address", resolver); put("port", 53); put("skipFallback", true)
                    put("domains", buildJsonArray { bootstrapDomains.forEach { add(JsonPrimitive("full:$it")) } })
                }) } })
            })
            put("inbounds", buildJsonArray { add(inbound(mainSocks, MAIN_TAG)); probes.forEach(::add) })
            put("outbounds", buildJsonArray {
                add(buildJsonObject { put("tag", BLOCK_TAG); put("protocol", "blackhole") })
                entries.forEach(::add)
                if (bootstrapDomains.isNotEmpty()) add(buildJsonObject {
                    put("tag", BOOTSTRAP_OUTBOUND_TAG); put("protocol", "freedom")
                })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    if (bootstrapDomains.isNotEmpty()) add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(JsonPrimitive(BOOTSTRAP_TAG)) })
                        put("ip", buildJsonArray { bootstrapResolvers.forEach { add(JsonPrimitive(it)) } })
                        put("port", "53"); put("network", "tcp,udp")
                        put("outboundTag", BOOTSTRAP_OUTBOUND_TAG)
                    })
                    if (probes.isNotEmpty()) {
                        add(rule(PRIMARY_PROBE_TAG, PRIMARY_TAG))
                        add(rule(BACKUP_PROBE_TAG, BACKUP_TAG))
                    }
                    add(rule(MAIN_TAG))
                })
                put("balancers", buildJsonArray { add(buildJsonObject {
                    put("tag", BALANCER_TAG)
                    // A healthy entry is selected only by an acknowledged API override.
                    put("selector", buildJsonArray { add(JsonPrimitive(BLOCK_TAG)) })
                    put("strategy", buildJsonObject { put("type", "random") })
                }) })
            })
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }
}
