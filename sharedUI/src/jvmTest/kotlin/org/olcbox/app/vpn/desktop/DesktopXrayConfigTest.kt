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
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopXrayConfigTest {
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
    fun identifiesVlessTransportWithoutSelectingXrayForWebsocket() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_VLESS,
            uri = "vless://00000000-0000-4000-8000-000000000000@example.com:443?type=ws"
        )

        assertFalse(DesktopXrayConfig.supports(profile))
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
        assertEquals("xhttp", stream.getValue("network").jsonPrimitive.content)
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
}
