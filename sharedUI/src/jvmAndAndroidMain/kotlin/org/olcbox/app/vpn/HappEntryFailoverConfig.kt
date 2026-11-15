package org.olcbox.app.vpn

import java.net.URI
import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnProfileConfig

/** Standalone Xray JSON for Happ, whose runner passes imported JSON to the core unchanged. */
internal object HappEntryFailoverConfig {
    const val PRIMARY_TAG = "entry-primary"
    const val BACKUP_TAG = "entry-backup"
    const val BALANCER_TAG = "entry-auto"
    private val json = Json { prettyPrint = true }

    /**
     * Native observatory uses one failed/successful HTTPS request, without the app policy's hold time.
     * Literal entry IPs avoid depending on the runner's bootstrap DNS when its TUN is active.
     */
    fun build(
        profile: VpnProfileConfig,
        primaryEndpoint: VpnEntryEndpoint,
        backupEndpoint: VpnEntryEndpoint,
        socksPort: Int = 10808,
        httpPort: Int = 10809,
        probeUrl: String = "https://www.gstatic.com/generate_204",
        probeIntervalSeconds: Int = 10
    ): String {
        val primary = primaryEndpoint.normalizedOrNull() ?: error("Invalid primary entry endpoint")
        val backup = backupEndpoint.normalizedOrNull() ?: error("Invalid backup entry endpoint")
        require(primary.host != backup.host || primary.port != backup.port) { "Entry endpoints must differ" }
        require(socksPort in 1..65535 && httpPort in 1..65535 && socksPort != httpPort) { "Invalid Happ listener ports" }
        val probe = URI(probeUrl)
        require(probe.scheme == "https" && !probe.host.isNullOrBlank() && probe.rawUserInfo == null &&
            probe.rawFragment == null && probe.port in -1..65535 && probe.port != 0) { "An HTTPS health URL is required" }
        require(probeIntervalSeconds in 1..3600) { "Invalid health interval" }
        require(XrayVlessConfig.downloadEndpointHost(profile) == null) {
            "Entry failover cannot replace a separate XHTTP download endpoint"
        }
        val cleanProfile = profile.copy(entryFailover = null)
        fun outbound(endpoint: VpnEntryEndpoint, tag: String): JsonObject {
            val base = Json.parseToJsonElement(XrayVlessConfig.build(cleanProfile,
                XraySocksSettings(port = socksPort), endpointAddress = endpoint.host)).jsonObject
            val original = base.getValue("outbounds").jsonArray.first().jsonObject
            val stream = original.getValue("streamSettings").jsonObject
            require(stream["security"] in setOf(JsonPrimitive("tls"), JsonPrimitive("reality"))) {
                "Entry failover requires verified transport security"
            }
            require(stream["tlsSettings"]?.jsonObject?.get("allowInsecure") != JsonPrimitive(true)) {
                "Entry failover requires certificate verification"
            }
            val settings = original.getValue("settings").jsonObject
            val peer = settings.getValue("vnext").jsonArray.single().jsonObject
            val pinnedPeer = JsonObject(peer + ("port" to JsonPrimitive(endpoint.port)))
            val pinnedSettings = JsonObject(settings + ("vnext" to JsonArray(listOf(pinnedPeer))))
            return JsonObject(original + mapOf("tag" to JsonPrimitive(tag), "settings" to pinnedSettings))
        }
        fun inbound(port: Int, protocol: String) = buildJsonObject {
            put("tag", protocol)
            put("listen", "127.0.0.1")
            put("port", port)
            put("protocol", protocol)
            put("settings", buildJsonObject {
                if (protocol == "socks") { put("auth", "noauth"); put("udp", true) }
            })
        }
        val root = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning"); put("dnsLog", false) })
            put("inbounds", buildJsonArray { add(inbound(socksPort, "socks")); add(inbound(httpPort, "http")) })
            put("outbounds", buildJsonArray {
                // Unmatched or failed routing never falls through to a direct internet connection.
                add(buildJsonObject { put("tag", "entry-block"); put("protocol", "blackhole") })
                add(outbound(primary, PRIMARY_TAG)); add(outbound(backup, BACKUP_TAG))
            })
            put("observatory", buildJsonObject {
                put("subjectSelector", buildJsonArray { add(JsonPrimitive(PRIMARY_TAG)) })
                put("probeURL", probeUrl)
                put("probeInterval", "${probeIntervalSeconds}s")
                put("enableConcurrency", true)
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                // Include any TUN inbound supplied by the host app, as well as these local proxies.
                put("rules", buildJsonArray { add(buildJsonObject {
                    put("type", "field"); put("network", "tcp,udp"); put("balancerTag", BALANCER_TAG)
                }) })
                put("balancers", buildJsonArray { add(buildJsonObject {
                    put("tag", BALANCER_TAG)
                    put("selector", buildJsonArray { add(JsonPrimitive(PRIMARY_TAG)) })
                    put("fallbackTag", BACKUP_TAG)
                    put("strategy", buildJsonObject { put("type", "random") })
                }) })
            })
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }
}
