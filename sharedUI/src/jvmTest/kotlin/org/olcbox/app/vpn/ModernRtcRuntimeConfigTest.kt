package org.olcbox.app.vpn

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.LocationConfig
import kotlin.test.*

class ModernRtcRuntimeConfigTest {
    private val jazz = LocationConfig("Jazz", "testcode:testpassword", "ab".repeat(32), "salutejazz")
    private val mts = LocationConfig("MTS", "https://my.mts-link.ru/j/123/456", "cd".repeat(32), "mtslink")

    @Test fun providersUseTheirActualEngineAndTransport() {
        for ((location, transport) in listOf(jazz to "datachannel", mts to "vp8channel")) {
            val config = Json.parseToJsonElement(VkCallsRuntimeConfig.build(location, 19001, "user", "pass", "1.1.1.1:53")).jsonObject
            assertEquals(location.bypassProvider, config.getValue("auth").jsonObject.getValue("provider").jsonPrimitive.content)
            assertEquals(transport, config.getValue("net").jsonObject.getValue("transport").jsonPrimitive.content)
            assertEquals(location.bypassProvider, VkCallsRuntimeConfig.engineType(location))
            assertEquals("127.0.0.1", config.getValue("socks").jsonObject.getValue("host").jsonPrimitive.content)
        }
        assertEquals("VP9", mts.normalized().transportName())
    }

    @Test fun validatesProviderRoomBeforeStartingAnyProcess() {
        for (room in listOf("https://evil.example/j/123/456", "http://my.mts-link.ru/j/123/456", "https://my.mts-link.ru@evil.example/j/123/456", "https://my.mts-link.ru/j/abc/456", "https://my.mts-link.ru/j/123/456#secret")) {
            assertFailsWith<IllegalArgumentException> { VkCallsRuntimeConfig.build(mts.copy(id=room), 19001, "", "", "1.1.1.1:53") }
        }
        assertTrue(VkCallsRuntimeConfig.validRoom(mts.copy(id=mts.id+"/session/789")))
        assertFalse(VkCallsRuntimeConfig.validRoom(jazz.copy(id="code:")))
        assertFalse(VkCallsRuntimeConfig.validRoom(jazz.copy(id="bad room:password")))
    }

    @Test fun preservesLegacyJazzAndVkWhileAddingNewProviders() {
        assertFalse(LocationConfig(bypassProvider="jazz").usesModernRtcEngine())
        assertTrue(LocationConfig(bypassProvider="salute-jazz").usesModernRtcEngine())
        assertTrue(LocationConfig(bypassProvider="mts-link").usesModernRtcEngine())
        assertTrue(LocationConfig(bypassProvider="vkcalls").usesModernRtcEngine())
        assertEquals("datachannel", jazz.normalized().transport)
        assertEquals("vp8channel", mts.copy(transport="datachannel").normalized().transport)
    }

    @Test fun newCarrierDiagnosticsNeverContainRoomKeysOrSocksPassword() {
        val jazzSafe = VkCallsRuntimeConfig.sanitizedOutput("room=testcode password=testpassword",jazz,"","")
        assertFalse(jazzSafe.contains("testcode")); assertFalse(jazzSafe.contains("testpassword"))
        for (location in listOf(jazz,mts)) {
            val safe = VkCallsRuntimeConfig.sanitizedOutput("room=${location.id} key=${location.key} pass=privatepass https://signaling.example/?token=secret",location,"privateuser","privatepass")
            assertFalse(safe.contains(location.id)); assertFalse(safe.contains(location.key))
            assertFalse(safe.contains("privatepass"));assertFalse(safe.contains("token=secret"))
        }
    }
}
