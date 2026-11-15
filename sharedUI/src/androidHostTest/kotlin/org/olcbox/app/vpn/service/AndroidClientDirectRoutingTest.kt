package org.olcbox.app.vpn.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.model.ClientRoutingPolicy
import org.olcbox.app.vpn.AdBlockingConfig
import org.olcbox.app.vpn.XrayEntryFailoverConfig
import org.olcbox.app.vpn.XraySocksSettings
import kotlin.test.*

class AndroidClientDirectRoutingTest {
    private val main = XraySocksSettings(port = 13080, username = "main-user", password = "main-password")
    private val primary = XraySocksSettings(port = 13081, username = "primary-user", password = "primary-password")
    private val backup = XraySocksSettings(port = 13082, username = "backup-user", password = "backup-password")
    private val profile = VpnProfileConfig(type = "vless", uri =
        "vless://00000000-0000-4000-8000-000000000000@ru-kvn.meesharr.space:443?type=ws&security=tls&sni=ru-kvn.meesharr.space&host=ru-kvn.meesharr.space&path=%2Fsynthetic")

    @Test fun standaloneVlessAddsLocalPolicyWithoutChangingTheVpnIdentity() {
        val old = Json.parseToJsonElement(buildAndroidNativeProxyConfig(profile, main, false)).jsonObject
        val routed = Json.parseToJsonElement(buildAndroidNativeProxyConfig(profile, main, true)).jsonObject
        fun proxy(config: kotlinx.serialization.json.JsonObject) = config.getValue("outbounds").jsonArray
            .single { it.jsonObject["protocol"]?.jsonPrimitive?.content == "vless" }.jsonObject
        assertEquals(proxy(old), proxy(routed))
        val sniffing = routed.getValue("inbounds").jsonArray.first().jsonObject.getValue("sniffing").jsonObject
        assertTrue(sniffing.getValue("destOverride").jsonArray.any { it.jsonPrimitive.content == "fakedns" })
        assertTrue(routed.getValue("outbounds").jsonArray.any { it.jsonObject["protocol"]?.jsonPrimitive?.content == "dns" })
    }

    @Test fun subscriptionPolicyReplacesBundledRulesForBothNativeAndFrontConfigs() {
        val remote = profile.copy(routingPolicy = ClientRoutingPolicy(revision = "fixture-2",
            directDomains = listOf("domain:bank.fixture.test", "full:media.fixture.test")))
        for (raw in listOf(buildAndroidNativeProxyConfig(remote, main, true),
            buildAndroidDirectSocksConfig(main, primary, remote))) {
            val config = Json.parseToJsonElement(raw).jsonObject
            val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
            val direct = rules.single { it["outboundTag"]?.jsonPrimitive?.content == "policy-direct" }
            assertEquals(listOf("domain:bank.fixture.test", "full:media.fixture.test"),
                direct.getValue("domain").jsonArray.map { it.jsonPrimitive.content })
            val dns = config.getValue("dns").jsonObject.getValue("servers").jsonArray.map { it.jsonObject }
                .single { it["tag"]?.jsonPrimitive?.content == "policy-dns-domestic" }
            assertEquals(direct.getValue("domain"), dns.getValue("domains"))
            assertFalse(direct.toString().contains("max.ru"), "A subscription revision must replace the old set")
        }
    }

    @Test fun failoverHealthProbesRetainTheirFixedEntryRouting() {
        val original = XrayEntryFailoverConfig.buildVless(profile, main,
            VpnEntryEndpoint("ru-kvn.meesharr.space", 443), VpnEntryEndpoint("jh-kvn.meesharr.space", 443),
            primary, backup, "/data/user/0/test/no_backup/ef/a.sock")
        val base = Json.parseToJsonElement(original).jsonObject
        val routed = Json.parseToJsonElement(applyAndroidClientDirectRouting(original, true)).jsonObject
        for ((inboundTag, outboundTag) in listOf("entry-probe-primary" to "entry-primary", "entry-probe-backup" to "entry-backup")) {
            fun inbound(config: kotlinx.serialization.json.JsonObject) = config.getValue("inbounds").jsonArray
                .single { it.jsonObject["tag"]?.jsonPrimitive?.content == inboundTag }
            assertEquals(inbound(base), inbound(routed))
            val rule = routed.getValue("routing").jsonObject.getValue("rules").jsonArray.first {
                it.jsonObject["inboundTag"]?.jsonArray?.any { tag -> tag.jsonPrimitive.content == inboundTag } == true
            }.jsonObject
            assertEquals(outboundTag, rule.getValue("outboundTag").jsonPrimitive.content)
        }
        assertEquals(original, applyAndroidClientDirectRouting(original, false))
    }

    @Test fun standaloneAwgFrontContainsOneBackendAndNoSecondPeerOrControlApi() {
        val config = Json.parseToJsonElement(buildAndroidDirectSocksConfig(main, primary)).jsonObject
        val backend = config.getValue("outbounds").jsonArray.single { it.jsonObject["protocol"]?.jsonPrimitive?.content == "socks" }.jsonObject
        val server = backend.getValue("settings").jsonObject.getValue("servers").jsonArray.single().jsonObject
        assertEquals(primary.port.toString(), server.getValue("port").jsonPrimitive.content)
        assertEquals(primary.username, server.getValue("users").jsonArray.single().jsonObject.getValue("user").jsonPrimitive.content)
        assertNull(config["api"])
        assertFalse(config.toString().contains("private_key"))
    }

    @Test fun adBlockingRetainsVisibleDnsForBothVpnProtocols() {
        for (type in listOf(VpnProfileConfig.TYPE_VLESS, VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)) {
            val config = Json.parseToJsonElement(AdBlockingConfig.build("127.0.0.1", 13079, "user", "password",
                main.host, main.port, "/private/synthetic-rules.json", main.username, main.password,
                preserveUpstreamDns = androidTunBridgePreservesDns(type, true))).jsonObject
            val dns = config.getValue("dns").jsonObject.getValue("servers").jsonArray.single().jsonObject
            assertEquals("tcp", dns.getValue("type").jsonPrimitive.content)
            assertEquals("53", dns.getValue("server_port").jsonPrimitive.content)
        }
        assertFalse(androidTunBridgePreservesDns(VpnProfileConfig.TYPE_VLESS, false))
        assertTrue(androidTunBridgePreservesDns(VpnProfileConfig.TYPE_AMNEZIA_WG, false))
    }
}
