package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSingBoxConfigTest {
    @Test
    fun awg31FlagsAreBooleanAndKeepaliveRangesArePreserved() {
        val endpoint = buildAwgOptions("RandomTrailers = 1\nDisableCookies = off", "22-30")
        val awg = endpoint.getValue("amnezia_wg").jsonObject
        assertEquals(true, awg.getValue("random_trailers").jsonPrimitive.boolean)
        assertEquals(false, awg.getValue("disable_cookies").jsonPrimitive.boolean)
        assertEquals("22-30", endpoint.getValue("peers").jsonArray.single().jsonObject
            .getValue("persistent_keepalive_interval").jsonPrimitive.content)
    }

    @Test
    fun legacyAwgDoesNotAcquireNewFlagsAndNumericKeepaliveStaysNumeric() {
        val endpoint = buildAwgOptions("Jc = 4", "25")
        val awg = endpoint.getValue("amnezia_wg").jsonObject
        assertNull(awg["random_trailers"])
        assertNull(awg["disable_cookies"])
        assertEquals(25, endpoint.getValue("peers").jsonArray.single().jsonObject
            .getValue("persistent_keepalive_interval").jsonPrimitive.int)
        assertNull(buildAwgOptions()["amnezia_wg"])
    }

    @Test
    fun rejectsInvalidExplicitAwgFlagsAndKeepaliveInsteadOfIgnoringThem() {
        for (field in listOf("RandomTrailers", "DisableCookies")) {
            assertFailsWith<IllegalArgumentException> { buildAwgOptions("$field = invalid") }
        }
        assertFailsWith<IllegalArgumentException> { buildAwgOptions(keepalive = "30-20") }
        assertFailsWith<IllegalArgumentException> { buildAwgOptions(keepalive = "25-999999999999999999999") }
    }

    private fun buildAwgOptions(options: String = "", keepalive: String? = null) = Json.parseToJsonElement(
        DesktopSingBoxConfig.build(VpnProfileConfig(type = VpnProfileConfig.TYPE_AMNEZIA_WG, rawConfig = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            $options
            [Peer]
            PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
            Endpoint = 203.0.113.42:55424
            ${keepalive?.let { "PersistentKeepalive = $it" }.orEmpty()}
        """.trimIndent()), DesktopSocksProxySettings())
    ).jsonObject.getValue("endpoints").jsonArray.single().jsonObject

    @Test
    fun tunPinsAmneziaEndpointAndKeepsTunnelDns() {
        val profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_AMNEZIA_WG, rawConfig = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            DNS = 9.9.9.9
            [Peer]
            PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = vpn.example.com:55424
        """.trimIndent())
        val root = Json.parseToJsonElement(DesktopSingBoxConfig.build(
            profile, DesktopSocksProxySettings(), endpointAddress = "203.0.113.42"
        )).jsonObject
        val peer = root.getValue("endpoints").jsonArray.single().jsonObject.getValue("peers").jsonArray.single().jsonObject
        assertEquals("203.0.113.42", peer.getValue("address").jsonPrimitive.content)
        assertEquals(55424, peer.getValue("port").jsonPrimitive.int)
        assertEquals("dns-tunnel-0", root.getValue("dns").jsonObject.getValue("final").jsonPrimitive.content)
        val dnsRule = root.getValue("route").jsonObject.getValue("rules").jsonArray.first().jsonObject
        assertEquals("local-socks", dnsRule.getValue("inbound").jsonPrimitive.content)
        assertEquals(53, dnsRule.getValue("port").jsonPrimitive.int)
        assertEquals("hijack-dns", dnsRule.getValue("action").jsonPrimitive.content)
    }

    @Test
    fun tunDnsUsesPrivateProfileResolverOverAwgWithoutChangingProxyRules() {
        val profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_AMNEZIA_WG, rawConfig = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            DNS = 10.8.1.1:5353
            [Peer]
            PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 203.0.113.42:55424
        """.trimIndent())
        val config = DesktopSingBoxConfig.build(profile, DesktopSocksProxySettings(), "203.0.113.42")
        val root = Json.parseToJsonElement(config).jsonObject
        val dns = root.getValue("dns").jsonObject
        val resolver = dns.getValue("servers").jsonArray[1].jsonObject
        assertEquals("10.8.1.1", resolver.getValue("server").jsonPrimitive.content)
        assertEquals(5353, resolver.getValue("server_port").jsonPrimitive.int)
        assertEquals("tcp", resolver.getValue("type").jsonPrimitive.content)
        assertEquals("amnezia-wireguard", resolver.getValue("detour").jsonPrimitive.content)
        assertEquals("dns-tunnel-0", dns.getValue("final").jsonPrimitive.content)
        val proxyRoot = Json.parseToJsonElement(
            DesktopSingBoxConfig.build(profile, DesktopSocksProxySettings())
        ).jsonObject
        val proxyRules = proxyRoot.getValue("route").jsonObject.getValue("rules").jsonArray
        assertEquals("sniff", proxyRules.single().jsonObject.getValue("action").jsonPrimitive.content)

        val binary = System.getenv("SING_BOX_AWG_BINARY")?.takeIf(String::isNotBlank) ?: return
        val path = Files.createTempFile("unifiedvpn-tun-dns-synthetic-", ".json")
        try {
            Files.writeString(path, config)
            val process = ProcessBuilder(binary, "check", "-c", path.toString()).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "sing-box config check timed out")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), output)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun buildsNestedAmneziaOptionsAndAuthenticatedSocksInbound() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_AMNEZIA_WG,
            rawConfig = """
                [Interface]
                Address = 10.8.1.2/32
                DNS = 9.9.9.9, 149.112.112.112
                PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                MTU = 1280
                Jc = 5
                Jmin = 10
                Jmax = 50
                S1 = 24
                S2 = 64
                H1 = 1
                H2 = 2
                H3 = 3
                H4 = 4
                HeaderProtectionKey = test-key
                RekeyAfterTime = 22-30

                [Peer]
                PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
                PresharedKey = 5WVe2Y4SEu4z91bAPWfTY8PU6bBNo/XZ1ZdaK6D1hOc=
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = 203.0.113.10:55424
                PersistentKeepalive = 25
            """.trimIndent()
        )

        val root = Json.parseToJsonElement(
            DesktopSingBoxConfig.build(
                profile,
                DesktopSocksProxySettings(port = 10900, username = "local", password = "secret")
            )
        ).jsonObject
        val endpoint = root.getValue("endpoints").jsonArray.single().jsonObject
        val dns = root.getValue("dns").jsonObject
        val dnsServers = dns.getValue("servers").jsonArray.map { it.jsonObject }
        val route = root.getValue("route").jsonObject
        val amnezia = endpoint.getValue("amnezia_wg").jsonObject
        val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
        val user = inbound.getValue("users").jsonArray.single().jsonObject

        assertNull(endpoint["jc"])
        assertEquals("dns-bootstrap", endpoint.getValue("domain_resolver").jsonPrimitive.content)
        assertEquals("dns-tunnel-0", dns.getValue("final").jsonPrimitive.content)
        assertEquals("ipv4_only", dns.getValue("strategy").jsonPrimitive.content)
        assertEquals("dns-tunnel-0", route.getValue("default_domain_resolver").jsonPrimitive.content)
        assertEquals("9.9.9.9", dnsServers[1].getValue("server").jsonPrimitive.content)
        assertEquals("tcp", dnsServers[1].getValue("type").jsonPrimitive.content)
        assertEquals("amnezia-wireguard", dnsServers[1].getValue("detour").jsonPrimitive.content)
        assertEquals(5, amnezia.getValue("jc").jsonPrimitive.int)
        assertEquals("1", amnezia.getValue("h1").jsonPrimitive.content)
        assertEquals("test-key", amnezia.getValue("header_protection_key").jsonPrimitive.content)
        assertEquals("22-30", amnezia.getValue("rekey_after_time").jsonPrimitive.content)
        assertEquals("local", user.getValue("username").jsonPrimitive.content)
        assertEquals("secret", user.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun rejectsWireGuardConfigWithoutPeerEndpoint() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_AMNEZIA_WG,
            rawConfig = """
                [Interface]
                Address = 10.8.1.2/32
                PrivateKey = private

                [Peer]
                PublicKey = public
            """.trimIndent()
        )

        assertFailsWith<IllegalArgumentException> {
            DesktopSingBoxConfig.build(profile, DesktopSocksProxySettings())
        }
    }

    @Test
    fun replacesUnresolvedAmneziaDnsPlaceholderWithSafeFallback() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_AMNEZIA_WG,
            rawConfig = """
                [Interface]
                Address = 10.8.1.2/32
                DNS = ${'$'}PRIMARY_DNS
                PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=

                [Peer]
                PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
                AllowedIPs = 0.0.0.0/0
                Endpoint = 203.0.113.10:55424
            """.trimIndent()
        )

        val root = Json.parseToJsonElement(
            DesktopSingBoxConfig.build(profile, DesktopSocksProxySettings(port = 10900))
        ).jsonObject
        val servers = root.getValue("dns").jsonObject.getValue("servers").jsonArray

        assertEquals("1.1.1.1", servers[1].jsonObject.getValue("server").jsonPrimitive.content)
    }

    @Test
    fun decodesNestedAmneziaBackupWithoutTreatingJsonAsWireGuardIni() {
        val wireGuardConfig = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Jc = 4

            [Peer]
            PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 203.0.113.10:55424
        """.trimIndent()
        val nestedConfig = Json.encodeToString(
            mapOf(
                "containers" to listOf(
                    mapOf(
                        "awg" to mapOf(
                            "last_config" to Json.encodeToString(mapOf("config" to wireGuardConfig))
                        )
                    )
                )
            )
        )
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_AMNEZIA_VPN,
            rawConfig = encodeVpnUri(nestedConfig)
        )

        val root = Json.parseToJsonElement(
            DesktopSingBoxConfig.build(profile, DesktopSocksProxySettings(port = 10900))
        ).jsonObject
        val endpoint = root.getValue("endpoints").jsonArray.single().jsonObject

        assertEquals(1, endpoint.getValue("peers").jsonArray.size)
        assertEquals(4, endpoint.getValue("amnezia_wg").jsonObject.getValue("jc").jsonPrimitive.int)
    }

    @Test
    fun buildsPrivateAmneziaProfileWhenConfigured() {
        val configPath = System.getenv("UNIFIEDVPN_PRIVATE_AWG_PROFILE")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val rawConfig = Files.readString(Path.of(configPath)).trim()
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_AMNEZIA_VPN,
            uri = rawConfig,
            rawConfig = rawConfig
        )

        val root = Json.parseToJsonElement(
            DesktopSingBoxConfig.build(profile, DesktopSocksProxySettings(port = 10900))
        ).jsonObject
        val endpoint = root.getValue("endpoints").jsonArray.single().jsonObject

        assertEquals(1, endpoint.getValue("peers").jsonArray.size)
        assertEquals(1, endpoint.getValue("address").jsonArray.size)
    }

    private fun encodeVpnUri(config: String): String {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(config.encodeToByteArray()) }
        }.toByteArray()
        val payload = ByteArray(4) + compressed
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }
}
