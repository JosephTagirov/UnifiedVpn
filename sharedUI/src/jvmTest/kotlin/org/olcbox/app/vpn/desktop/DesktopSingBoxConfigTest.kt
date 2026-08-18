package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopSingBoxConfigTest {
    @Test
    fun buildsNestedAmneziaOptionsAndAuthenticatedSocksInbound() {
        val profile = VpnProfileConfig(
            type = VpnProfileConfig.TYPE_AMNEZIA_WG,
            rawConfig = """
                [Interface]
                Address = 10.8.1.2/32
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
        val amnezia = endpoint.getValue("amnezia_wg").jsonObject
        val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
        val user = inbound.getValue("users").jsonArray.single().jsonObject

        assertNull(endpoint["jc"])
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
}
