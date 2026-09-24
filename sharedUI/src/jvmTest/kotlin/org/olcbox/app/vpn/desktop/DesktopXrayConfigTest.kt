package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopXrayConfigTest {
    @Test
    fun tunPinsEndpointWithoutChangingTlsIdentityOrHttpHost() {
        for (custom in listOf(false, true)) {
            val profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_VLESS,
                uri = "vless://00000000-0000-4000-8000-000000000000@vpn.example.com:443?type=xhttp&security=tls" +
                    if (custom) "&sni=tls.example.com&host=http.example.com" else ""
            )
            val root = Json.parseToJsonElement(DesktopXrayConfig.build(
                profile, DesktopSocksProxySettings(), endpointAddress = "203.0.113.42"
            )).jsonObject
            val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
            val address = outbound.getValue("settings").jsonObject.getValue("vnext").jsonArray.first().jsonObject
            val stream = outbound.getValue("streamSettings").jsonObject
            assertEquals("203.0.113.42", address.getValue("address").jsonPrimitive.content)
            assertEquals(if (custom) "tls.example.com" else "vpn.example.com",
                stream.getValue("tlsSettings").jsonObject.getValue("serverName").jsonPrimitive.content)
            assertEquals(if (custom) "http.example.com" else "vpn.example.com",
                stream.getValue("xhttpSettings").jsonObject.getValue("host").jsonPrimitive.content)
        }
    }

    @Test
    fun buildsXhttpRealityWithAuthenticatedSocksInbound() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_VLESS,
            uri = "vless://00000000-0000-4000-8000-000000000000@203.0.113.10:443" +
                "?type=xhttp&security=reality&encryption=none&sni=example.com&fp=chrome" +
                "&pbk=public-key&sid=0123456789abcdef&path=%2Fapi&host=edge.example.com" +
                "&mode=auto&spx=%2F"
        )

        val root = Json.parseToJsonElement(
            DesktopXrayConfig.build(
                profile,
                DesktopSocksProxySettings(port = 10920, username = "local", password = "secret")
            )
        ).jsonObject
        val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
        val inboundSettings = inbound.getValue("settings").jsonObject
        val account = inboundSettings.getValue("accounts").jsonArray.single().jsonObject
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
        val stream = outbound.getValue("streamSettings").jsonObject
        val xhttp = stream.getValue("xhttpSettings").jsonObject
        val reality = stream.getValue("realitySettings").jsonObject

        assertEquals(10920, inbound.getValue("port").jsonPrimitive.int)
        assertEquals("password", inboundSettings.getValue("auth").jsonPrimitive.content)
        assertEquals("local", account.getValue("user").jsonPrimitive.content)
        assertEquals("secret", account.getValue("pass").jsonPrimitive.content)
        assertEquals("xhttp", stream.getValue("network").jsonPrimitive.content)
        assertEquals("/api", xhttp.getValue("path").jsonPrimitive.content)
        assertEquals("edge.example.com", xhttp.getValue("host").jsonPrimitive.content)
        assertEquals("example.com", reality.getValue("serverName").jsonPrimitive.content)
        assertFalse(reality.getValue("show").jsonPrimitive.boolean)
        assertEquals("203.0.113.10", DesktopXrayConfig.endpointHost(profile))
        assertTrue(DesktopXrayConfig.supports(profile))
    }

    @Test
    fun supportsPinnedXrayTransportsAndNormalizesAliases() {
        for ((transport, network) in mapOf(
            "tcp" to "raw", "raw" to "raw", "ws" to "ws", "websocket" to "ws",
            "grpc" to "grpc", "httpupgrade" to "httpupgrade", "xhttp" to "xhttp",
            "splithttp" to "xhttp"
        )) {
            val profile = profile("type=$transport")
            assertTrue(DesktopXrayConfig.supports(profile), transport)
            val stream = streamSettings(profile)
            assertEquals(network, stream.getValue("network").jsonPrimitive.content)
            assertEquals(network == "xhttp", stream.containsKey("xhttpSettings"), transport)
        }
    }

    @Test
    fun missingOrEmptyTransportUsesRawWithSocksUdp() {
        for (query in listOf("", "type=", "type=tcp&headerType=none", "type=raw")) {
            val root = Json.parseToJsonElement(
                DesktopXrayConfig.build(profile(query), DesktopSocksProxySettings())
            ).jsonObject
            val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
            val stream = root.getValue("outbounds").jsonArray.first().jsonObject
                .getValue("streamSettings").jsonObject
            assertTrue(inbound.getValue("settings").jsonObject.getValue("udp").jsonPrimitive.boolean)
            assertEquals("raw", stream.getValue("network").jsonPrimitive.content)
            assertEquals(setOf("network", "security"), stream.keys)
        }
    }

    @Test
    fun unsupportedTransportsAreNotSilentlyConvertedToXhttp() {
        for (transport in listOf("quic", "http", "h2", "h3", "kcp", "unknown")) {
            val profile = profile("type=$transport")
            assertFalse(DesktopXrayConfig.supports(profile))
            val error = assertFailsWith<IllegalArgumentException> {
                DesktopXrayConfig.build(profile, DesktopSocksProxySettings())
            }
            assertTrue(error.message.orEmpty().contains("Unsupported desktop VLESS transport"))
        }
    }

    @Test
    fun webTransportsKeepPathHostAndTlsOptions() {
        for ((transport, settingsName) in mapOf("ws" to "wsSettings", "httpupgrade" to "httpupgradeSettings")) {
            val stream = streamSettings(profile(
                "type=$transport&security=tls&path=%2Fsocket%3Fed%3D2048&host=edge.example.com" +
                    "&sni=tls.example.com&fp=chrome&alpn=http%2F1.1&allowInsecure=false"
            ))
            val transportSettings = stream.getValue(settingsName).jsonObject
            val tls = stream.getValue("tlsSettings").jsonObject
            assertEquals("/socket?ed=2048", transportSettings.getValue("path").jsonPrimitive.content)
            assertEquals("edge.example.com", transportSettings.getValue("host").jsonPrimitive.content)
            assertEquals("tls.example.com", tls.getValue("serverName").jsonPrimitive.content)
            assertEquals("chrome", tls.getValue("fingerprint").jsonPrimitive.content)
            assertEquals("http/1.1", tls.getValue("alpn").jsonArray.single().jsonPrimitive.content)
            assertFalse(tls.getValue("allowInsecure").jsonPrimitive.boolean)
        }
    }

    @Test
    fun grpcKeepsServiceNameAuthorityAndMultiMode() {
        for (serviceParameter in listOf("serviceName", "service_name")) {
            val stream = streamSettings(profile(
                "type=grpc&security=tls&$serviceParameter=service%2Fname&mode=multi" +
                    "&authority=grpc.example.com&sni=tls.example.com"
            ), pinnedEndpoint = true)
            val grpc = stream.getValue("grpcSettings").jsonObject
            assertEquals("service/name", grpc.getValue("serviceName").jsonPrimitive.content)
            assertEquals("grpc.example.com", grpc.getValue("authority").jsonPrimitive.content)
            assertTrue(grpc.getValue("multiMode").jsonPrimitive.boolean)
        }
        val grpc = streamSettings(profile("type=grpc&mode=gun"))
            .getValue("grpcSettings").jsonObject
        assertFalse(grpc.getValue("multiMode").jsonPrimitive.boolean)
        assertEquals("", grpc.getValue("serviceName").jsonPrimitive.content)
    }

    @Test
    fun pinnedEndpointsRetainOriginalTlsAndHttpIdentityForEveryTransport() {
        for (transport in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            for (sni in listOf(null, "tls.example.com")) {
                val stream = streamSettings(profile(
                    "type=$transport&security=tls" + (sni?.let { "&sni=$it" } ?: "")
                ), pinnedEndpoint = true)
                val expectedHost = sni ?: "vpn.example.com"
                assertEquals(expectedHost,
                    stream.getValue("tlsSettings").jsonObject.getValue("serverName").jsonPrimitive.content)
                when (transport) {
                    "ws", "httpupgrade", "xhttp" -> assertEquals(expectedHost,
                        stream.getValue("${transport}Settings").jsonObject.getValue("host").jsonPrimitive.content)
                    "grpc" -> assertEquals(expectedHost,
                        stream.getValue("grpcSettings").jsonObject.getValue("authority").jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun pinnedCleartextTransportsKeepOriginalHttpHostWithoutTls() {
        for (transport in listOf("ws", "grpc", "httpupgrade", "xhttp")) {
            val stream = streamSettings(profile("type=$transport"), pinnedEndpoint = true)
            val settings = stream.getValue("${transport}Settings").jsonObject
            assertEquals("vpn.example.com",
                settings.getValue(if (transport == "grpc") "authority" else "host").jsonPrimitive.content)
            assertFalse(stream.containsKey("tlsSettings"))
        }
    }

    @Test
    fun pinnedRawRealityKeepsServerIdentityAndFlow() {
        val root = Json.parseToJsonElement(DesktopXrayConfig.build(
            profile("type=tcp&security=reality&sni=reality.example.com&fp=chrome" +
                "&pbk=public-key&sid=0123456789abcdef&flow=xtls-rprx-vision"),
            DesktopSocksProxySettings(), endpointAddress = "203.0.113.42"
        )).jsonObject
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
        val endpoint = outbound.getValue("settings").jsonObject.getValue("vnext").jsonArray.single().jsonObject
        val reality = outbound.getValue("streamSettings").jsonObject.getValue("realitySettings").jsonObject
        assertEquals("203.0.113.42", endpoint.getValue("address").jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", endpoint.getValue("users").jsonArray.single().jsonObject
            .getValue("flow").jsonPrimitive.content)
        assertEquals("reality.example.com", reality.getValue("serverName").jsonPrimitive.content)
        assertEquals("public-key", reality.getValue("publicKey").jsonPrimitive.content)
        assertEquals("0123456789abcdef", reality.getValue("shortId").jsonPrimitive.content)
    }

    @Test
    fun pinnedGrpcRetainsRealityAndIpDefaultAuthority() {
        for ((host, query, expectedAuthority) in listOf(
            Triple("vpn.example.com", "security=reality&sni=reality.example.com", "vpn.example.com:443"),
            Triple("203.0.113.10", "", "203.0.113.10:443"),
            Triple("203.0.113.10", "security=tls", "203.0.113.10:443"),
            Triple("[2001:db8::1]", "", "[2001:db8::1]:443"),
            Triple("[2001:db8::1]", "security=tls&sni=tls.example.com", "tls.example.com")
        )) {
            val profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_VLESS,
                uri = "vless://00000000-0000-4000-8000-000000000000@$host:443?type=grpc&$query"
            )
            val grpc = streamSettings(profile, pinnedEndpoint = true).getValue("grpcSettings").jsonObject
            assertEquals(expectedAuthority, grpc.getValue("authority").jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsUnsupportedOptionsInsteadOfDiscardingThem() {
        for (query in listOf(
            "type=tcp&headerType=http", "type=grpc&mode=unknown",
            "type=ws&security=reality", "type=httpupgrade&security=reality"
        )) {
            assertFailsWith<IllegalArgumentException>(query) {
                DesktopXrayConfig.build(profile(query), DesktopSocksProxySettings())
            }
        }
    }

    @Test
    fun officialXrayAcceptsSupportedSyntheticConfigsWhenConfigured() {
        val xrayPath = System.getenv("XRAY_BINARY")?.trim()?.takeIf(String::isNotEmpty) ?: return
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        val reality = "security=reality&sni=reality.example.com&fp=chrome&pbk=$publicKey&sid=0123456789abcdef"
        val configPath = Files.createTempFile("unified-vpn-xray-synthetic-test-", ".json")
        try {
            for (query in listOf(
                "", "type=tcp&security=tls", "type=raw", "type=ws&security=tls&path=%2Fws",
                "type=websocket&host=edge.example.com", "type=httpupgrade&security=tls&path=%2Fupgrade",
                "type=grpc&security=tls&serviceName=test&mode=multi&authority=grpc.example.com",
                "type=grpc&serviceName=test&mode=gun", "type=xhttp&security=tls&mode=auto",
                "type=splithttp&path=%2Fsplit", "type=tcp&$reality&flow=xtls-rprx-vision",
                "type=grpc&$reality&serviceName=test"
            )) {
                Files.writeString(configPath, DesktopXrayConfig.build(
                    profile(query), DesktopSocksProxySettings(port = 10920, username = "test", password = "test"),
                    endpointAddress = "203.0.113.42"
                ))
                val process = ProcessBuilder(xrayPath, "run", "-test", "-config", configPath.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Xray synthetic config validation timed out")
                    assertEquals(0, process.exitValue(), "Xray rejected synthetic config: $query")
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        } finally {
            Files.deleteIfExists(configPath)
        }
    }

    @Test
    fun buildsPrivateVlessProfileWhenConfigured() {
        val configPath = System.getenv("UNIFIEDVPN_PRIVATE_VLESS_PROFILE")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val uri = Files.readString(Path.of(configPath)).trim()
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_VLESS,
            uri = uri,
            rawConfig = uri
        )

        val root = Json.parseToJsonElement(
            DesktopXrayConfig.build(profile, DesktopSocksProxySettings(port = 10920))
        ).jsonObject
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject
        val stream = outbound.getValue("streamSettings").jsonObject

        assertEquals("vless", outbound.getValue("protocol").jsonPrimitive.content)
        assertTrue(stream.getValue("network").jsonPrimitive.content in setOf("raw", "ws", "grpc", "httpupgrade", "xhttp"))
        assertTrue(DesktopXrayConfig.supports(profile))
        assertTrue(DesktopXrayConfig.endpointHost(profile).isNotBlank())
    }

    @Test
    fun officialXrayAcceptsPrivateGeneratedConfigWhenConfigured() {
        val profilePath = System.getenv("UNIFIEDVPN_PRIVATE_VLESS_PROFILE")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val xrayPath = System.getenv("XRAY_BINARY")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val uri = Files.readString(Path.of(profilePath)).trim()
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_VLESS,
            uri = uri,
            rawConfig = uri
        )
        val configPath = Files.createTempFile("unified-vpn-xray-test-", ".json")

        try {
            Files.writeString(
                configPath,
                DesktopXrayConfig.build(profile, DesktopSocksProxySettings(port = 10920))
            )
            val process = ProcessBuilder(
                xrayPath,
                "run",
                "-test",
                "-config",
                configPath.toAbsolutePath().toString()
            ).redirectErrorStream(true).start()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Xray config validation timed out")
            assertEquals(0, process.exitValue(), "Xray rejected the generated private test config")
        } finally {
            Files.deleteIfExists(configPath)
        }
    }

    private fun profile(query: String) = VpnProfileConfig(
        type = VpnProfileConfig.TYPE_VLESS,
        uri = "vless://00000000-0000-4000-8000-000000000000@vpn.example.com:443?$query"
    )

    private fun streamSettings(profile: VpnProfileConfig, pinnedEndpoint: Boolean = false) =
        Json.parseToJsonElement(DesktopXrayConfig.build(
            profile, DesktopSocksProxySettings(), endpointAddress = "203.0.113.42".takeIf { pinnedEndpoint }
        )).jsonObject.getValue("outbounds").jsonArray.first().jsonObject.getValue("streamSettings").jsonObject
}
