package org.olcbox.app.vpn

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnProfileConfig

/** Routes the published service allowlist through the device uplink, independently of the VPN transport. */
internal object ClientDirectRoutingConfig {
    const val DIRECT_TAG = "policy-direct"
    const val DNS_DIRECT_TAG = "policy-dns-domestic"
    const val FAKE_POOL = "198.19.0.0/16"
    private const val BLOCK_TAG = "policy-block"
    private const val DNS_OUT_TAG = "policy-dns-out"
    private const val REMOTE_TAG = "policy-dns-remote"
    private const val REMOTE_PIN_TAG = "policy-dns-remote-pin"
    private const val REMOTE_LOOP_TAG = "policy-dns-remote-loop"
    private const val REMOTE_HOP_TAG = "policy-dns-remote-pinned"
    private const val BOOTSTRAP_TAG = "policy-dns-bootstrap"
    private const val BOOTSTRAP_OUT_TAG = "policy-bootstrap-direct"
    private val json = Json { prettyPrint = true }

    fun apply(configJson: String, physicalInterface: String? = null, profile: VpnProfileConfig? = null): String =
        applyWithRules(configJson, profile?.let(ClientDirectPolicy::effectiveDomains) ?: ClientDirectPolicy.domainRules, physicalInterface)

    internal fun applyWithRules(configJson: String, directDomains: List<String>, physicalInterface: String? = null): String {
        require(directDomains.isNotEmpty() && directDomains.size <= 131072)
        require(directDomains.distinct().size == directDomains.size)
        require(directDomains.all { it.startsWith("domain:") || it.startsWith("full:") || it.startsWith("regexp:") })
        require(physicalInterface == null || (physicalInterface.isNotBlank() && physicalInterface.length <= 256 &&
            physicalInterface.none { it.code < 32 || it.code == 127 })) { "Invalid physical interface" }
        val root = Json.parseToJsonElement(configJson).jsonObject
        val originalOutbounds = root.getValue("outbounds").jsonArray.map { it.jsonObject }.toMutableList()
        if (originalOutbounds.any { it["tag"] == JsonPrimitive(DIRECT_TAG) }) {
            val currentInterface = originalOutbounds.first { it["tag"] == JsonPrimitive(DIRECT_TAG) }
                .get("streamSettings")?.jsonObject?.get("sockopt")?.jsonObject?.get("interface")?.jsonPrimitive?.content
            require(currentInterface == physicalInterface) { "An existing policy belongs to another physical interface" }
            val currentDomains = root["routing"]?.jsonObject?.get("rules")?.jsonArray.orEmpty()
                .map { it.jsonObject }.firstOrNull { it["outboundTag"] == JsonPrimitive(DIRECT_TAG) }
                ?.get("domain")?.jsonArray?.map { it.jsonPrimitive.content }
            require(currentDomains == directDomains) { "An existing policy belongs to another subscription revision" }
            return configJson
        }
        require(originalOutbounds.none { it["tag"]?.jsonPrimitive?.contentOrNull?.startsWith("policy-") == true })
        val originalRouting = root["routing"]?.jsonObject ?: buildJsonObject { }
        val balancers = originalRouting["balancers"]?.jsonArray.orEmpty()
        val fallback = if (balancers.any { it.jsonObject["tag"] == JsonPrimitive("entry-auto") }) {
            "balancerTag" to "entry-auto"
        } else {
            val index = originalOutbounds.indexOfFirst {
                it["protocol"]?.jsonPrimitive?.contentOrNull !in setOf("freedom", "blackhole", "dns", "loopback")
            }
            require(index >= 0) { "A VPN outbound is required before adding direct routing" }
            var outbound = originalOutbounds[index]
            if (outbound["tag"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                outbound = JsonObject(outbound + ("tag" to JsonPrimitive("upstream-vpn")))
                originalOutbounds[index] = outbound
            }
            "outboundTag" to outbound.getValue("tag").jsonPrimitive.content
        }
        val inbounds = root.getValue("inbounds").jsonArray.mapIndexed { index, element ->
            var inbound = element.jsonObject
            if (inbound["tag"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                inbound = JsonObject(inbound + ("tag" to JsonPrimitive("policy-main-$index")))
            }
            val tag = inbound.getValue("tag").jsonPrimitive.content
            if (tag.startsWith("entry-probe-")) inbound else JsonObject(inbound + ("sniffing" to buildJsonObject {
                put("enabled", true)
                put("destOverride", strings(listOf("fakedns", "http", "tls", "quic")))
                put("metadataOnly", false)
                // Fake-IP destinations must be restored to a real domain before dialing.
                put("routeOnly", false)
            }))
        }
        val userTags = inbounds.map { it.getValue("tag").jsonPrimitive.content }.filterNot { it.startsWith("entry-probe-") }
        require(userTags.isNotEmpty())
        val bootstrapDomains = endpointDomains(originalOutbounds)
        val outbounds = originalOutbounds + listOf(
            buildJsonObject { put("tag", BLOCK_TAG); put("protocol", "blackhole") },
            freedom(DIRECT_TAG, physicalInterface), freedom(DNS_DIRECT_TAG, physicalInterface),
            freedom(BOOTSTRAP_OUT_TAG, physicalInterface),
            freedom(REMOTE_PIN_TAG, null, REMOTE_LOOP_TAG),
            buildJsonObject { put("tag", REMOTE_LOOP_TAG); put("protocol", "loopback"); put("settings", buildJsonObject { put("inboundTag", REMOTE_HOP_TAG) }) },
            buildJsonObject { put("tag", DNS_OUT_TAG); put("protocol", "dns"); put("settings", buildJsonObject { }) }
        )
        val dns = buildJsonObject {
            put("hosts", buildJsonObject { put("cloudflare-dns.com", "1.1.1.1"); put("dns.google", "8.8.8.8") })
            // The current Android HEV/TUN and deployed AWG profiles capture IPv4 only.
            put("queryStrategy", "UseIPv4")
            put("disableFallbackIfMatch", true)
            put("servers", buildJsonArray {
                if (bootstrapDomains.isNotEmpty()) for (resolver in listOf("1.1.1.1", "8.8.8.8")) add(buildJsonObject {
                    put("address", resolver); put("domains", strings(bootstrapDomains.map { "full:$it" }))
                    put("skipFallback", true); put("tag", BOOTSTRAP_TAG)
                })
                // Only allowlisted names receive fake IPs. Other names keep normal remote DNS answers.
                add(buildJsonObject { put("address", "fakedns"); put("domains", strings(directDomains)); put("skipFallback", true) })
                add(buildJsonObject { put("address", "https://dns.google/dns-query"); put("domains", strings(directDomains)); put("skipFallback", true); put("tag", DNS_DIRECT_TAG) })
                add(buildJsonObject { put("address", "https://cloudflare-dns.com/dns-query"); put("tag", REMOTE_TAG) })
            })
        }
        val rules = buildJsonArray {
            // Health probes are bound to their own entry, and never use a service's direct exception.
            originalRouting["rules"]?.jsonArray?.filter { rule ->
                val tags = rule.jsonObject["inboundTag"]?.jsonArray ?: return@filter false
                tags.isNotEmpty() && tags.all { it.jsonPrimitive.content.startsWith("entry-probe-") }
            }?.forEach(::add)
            add(rule(DNS_DIRECT_TAG, "outboundTag" to DNS_DIRECT_TAG, domains = listOf("full:dns.google"), port = "443", network = "tcp"))
            add(rule(REMOTE_TAG, "outboundTag" to REMOTE_PIN_TAG, domains = listOf("full:cloudflare-dns.com"), port = "443", network = "tcp"))
            add(rule(REMOTE_HOP_TAG, fallback, ips = listOf("1.1.1.1"), port = "443", network = "tcp"))
            add(rule(BOOTSTRAP_TAG, "outboundTag" to BOOTSTRAP_OUT_TAG, ips = listOf("1.1.1.1", "8.8.8.8"), port = "53"))
            add(buildJsonObject { put("type", "field"); put("inboundTag", strings(listOf(DNS_DIRECT_TAG, REMOTE_TAG, REMOTE_HOP_TAG, BOOTSTRAP_TAG))); put("outboundTag", BLOCK_TAG) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", strings(userTags)); put("port", "53"); put("network", "tcp,udp"); put("outboundTag", DNS_OUT_TAG) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", strings(userTags)); put("domain", strings(directDomains)); put("network", "tcp,udp"); put("outboundTag", DIRECT_TAG) })
            // Old DNS cache entries cannot send unmapped synthetic addresses into the network.
            add(buildJsonObject { put("type", "field"); put("inboundTag", strings(userTags)); put("ip", strings(listOf("198.18.0.0/15"))); put("outboundTag", BLOCK_TAG) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", strings(userTags)); put("network", "tcp,udp"); put(fallback.first, fallback.second) })
            add(buildJsonObject { put("type", "field"); put("network", "tcp,udp"); put("outboundTag", BLOCK_TAG) })
        }
        val routing = JsonObject(originalRouting + mapOf("domainStrategy" to JsonPrimitive("AsIs"), "rules" to rules))
        val result = JsonObject(root + mapOf("inbounds" to JsonArray(inbounds), "outbounds" to JsonArray(outbounds),
            "dns" to dns, "routing" to routing, "fakedns" to buildJsonArray { add(buildJsonObject {
                // Avoid 198.18.0.1/30, used as the native TUN interface address on both platforms.
                put("ipPool", FAKE_POOL); put("poolSize", 65535)
            }) }))
        return json.encodeToString(JsonElement.serializer(), result)
    }

    fun buildSocksFront(socks: XraySocksSettings, upstream: XraySocksSettings, physicalInterface: String? = null,
                        profile: VpnProfileConfig? = null): String {
        require(socks.port in 1..65535 && upstream.port in 1..65535 && socks.port != upstream.port)
        require(upstream.host in setOf("127.0.0.1", "::1") && upstream.username.isNotBlank() && upstream.password.isNotBlank())
        require((socks.username.isBlank() && socks.password.isBlank()) || (socks.username.isNotBlank() && socks.password.isNotBlank()))
        val base = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning"); put("dnsLog", false) })
            put("inbounds", buildJsonArray { add(buildJsonObject {
                put("tag", "user-socks"); put("listen", socks.host); put("port", socks.port); put("protocol", "socks")
                put("settings", buildJsonObject {
                    put("udp", true); put("auth", if (socks.username.isBlank()) "noauth" else "password")
                    if (socks.username.isNotBlank()) put("accounts", buildJsonArray { add(buildJsonObject { put("user", socks.username); put("pass", socks.password) }) })
                })
            }) })
            put("outbounds", buildJsonArray { add(buildJsonObject {
                put("tag", "upstream-vpn"); put("protocol", "socks")
                put("settings", buildJsonObject { put("servers", buildJsonArray { add(buildJsonObject {
                    put("address", upstream.host); put("port", upstream.port)
                    put("users", buildJsonArray { add(buildJsonObject { put("user", upstream.username); put("pass", upstream.password) }) })
                }) }) })
            }) })
        }.toString()
        return apply(base, physicalInterface, profile)
    }

    private fun freedom(tag: String, physicalInterface: String?, dialerProxy: String? = null) = buildJsonObject {
        put("tag", tag); put("protocol", "freedom")
        put("streamSettings", buildJsonObject { put("sockopt", buildJsonObject {
            put("domainStrategy", "ForceIPv4")
            physicalInterface?.let { put("interface", it) }
            dialerProxy?.let { put("dialerProxy", it) }
        }) })
    }

    private fun rule(inbound: String, target: Pair<String, String>, domains: List<String>? = null,
                     ips: List<String>? = null, port: String? = null, network: String = "tcp,udp") = buildJsonObject {
        put("type", "field"); put("inboundTag", strings(listOf(inbound))); put("network", network)
        domains?.let { put("domain", strings(it)) }; ips?.let { put("ip", strings(it)) }; port?.let { put("port", it) }
        put(target.first, target.second)
    }

    private fun endpointDomains(outbounds: List<JsonObject>): List<String> = outbounds.flatMap { outbound ->
        val settings = outbound["settings"]?.jsonObject
        val peers = settings?.get("vnext")?.jsonArray ?: settings?.get("servers")?.jsonArray
        val primary = peers.orEmpty().mapNotNull { peer -> peer.jsonObject["address"]?.jsonPrimitive?.contentOrNull }
        val download = outbound["streamSettings"]?.jsonObject?.get("xhttpSettings")?.jsonObject
            ?.get("extra")?.jsonObject?.get("downloadSettings")?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
        primary + listOfNotNull(download)
    }.filter { host -> host != "localhost" && ':' !in host && host.any { it in 'a'..'z' || it in 'A'..'Z' } }.distinct()

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
