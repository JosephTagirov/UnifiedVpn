package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.share.ConfigShareService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VkCallsRuntimeConfigTest {
    private val location = LocationConfig(
        name = "VK test", bypassProvider = "vk_calls", id = "https://vk.ru/call/join/test-room_123",
        key = "ab".repeat(32), transport = "datachannel"
    )

    @Test fun normalizesProviderWithoutChangingJitsi() {
        assertTrue(location.isVkCalls())
        assertEquals("vkcalls", location.normalized().bypassProvider)
        assertEquals("vp8channel", location.normalized().transport)
        assertEquals(listOf("vp8channel"), LocationConfig.supportedTransportsForProvider("vk"))
        assertEquals("datachannel", LocationConfig.defaultTransportForProvider("jitsi"))
        assertFalse(LocationConfig(bypassProvider = "jitsi").isVkCalls())
    }

    @Test fun buildsSeparateAuthenticatedClientContract() {
        val config = Json.parseToJsonElement(VkCallsRuntimeConfig.build(location, 19871, "test-user", "test-pass", "1.1.1.1:53")).jsonObject
        assertEquals("cnc", config.getValue("mode").jsonPrimitive.content)
        assertEquals("vkcalls", config.getValue("auth").jsonObject.getValue("provider").jsonPrimitive.content)
        assertEquals("127.0.0.1", config.getValue("socks").jsonObject.getValue("host").jsonPrimitive.content)
        assertEquals("vp8channel", config.getValue("net").jsonObject.getValue("transport").jsonPrimitive.content)
        assertEquals(VkCallsRuntimeConfig.DTLS_PROFILE, config.getValue("dtls").jsonObject.getValue("profile").jsonPrimitive.content)
    }

    @Test fun shareLinkImportsWithoutLosingVkProviderRoomKeyOrVp8Parameters() = runBlocking {
        val directory = Files.createTempDirectory("vk-share-test").toFile()
        try {
            val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(directory.toPath()))
            assertTrue(repository.importText(ConfigShareService.olcRtcUri(location)))
            assertEquals(location.normalized(), repository.getAllLocations().single().location)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsUnsafeRoomAndCredentials() {
        listOf("http://vk.ru/call/join/id", "https://vk.ru.evil.test/call/join/id",
            "https://user@vk.ru/call/join/id", "https://vk.ru/call/join/id?token=secret",
            "https://vk.ru/call/join/id#fragment", "https://vk.ru:444/call/join/id",
            "https://vk.ru/call/join/id%0Ainjection").forEach { room ->
            assertFalse(VkCallsRuntimeConfig.validRoom(room), room)
            assertFailsWith<IllegalArgumentException> { VkCallsRuntimeConfig.build(location.copy(id = room), 19871, "u", "p", "1.1.1.1:53") }
        }
        assertFailsWith<IllegalArgumentException> { VkCallsRuntimeConfig.build(location, 19871, "user", "", "1.1.1.1:53") }
        val loopback = Json.parseToJsonElement(VkCallsRuntimeConfig.build(location, 19871, "", "", "1.1.1.1:53")).jsonObject
        assertEquals("127.0.0.1", loopback.getValue("socks").jsonObject.getValue("host").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { VkCallsRuntimeConfig.build(location, 0, "u", "p", "1.1.1.1:53") }
        assertFailsWith<IllegalArgumentException> { VkCallsRuntimeConfig.build(location, 19871, "u", "p", "") }
    }

    @Test fun diagnosticsDoNotExposeRoomKeyCredentialsOrSignalingTokens() {
        val raw = "vkcalls ${location.id} ${location.key} secret-user secret-pass wss://edge.vk.ru/path?anon=short-token"
        val safe = VkCallsRuntimeConfig.sanitizedOutput(raw, location, "secret-user", "secret-pass")
        listOf(location.id, "test-room_123", location.key, "secret-user", "secret-pass", "short-token").forEach {
            assertFalse(it in safe)
        }
    }
}
