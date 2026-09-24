package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxProfileConfigTest {
    @Test
    fun profilesWithoutTransportKeepTheClassicDefault() {
        val parsed = assertNotNull(OpenFluxProfileConfig.parse(
            """{"document_url":"$DOCUMENT_URL","encryption_key":"$KEY"}"""
        ))

        assertEquals(CONFIG, parsed)
        assertEquals("yandex", OpenFluxProfileConfig().transport)
        assertEquals("yandex", Json.parseToJsonElement(parsed.toEngineJson()).jsonObject
            .getValue("transport").jsonPrimitive.content)
    }

    @Test
    fun volgaTransportRoundTripsWithoutChangingTheClassicProfile() {
        val config = CONFIG.copy(transport = "vyandex")
        val raw = config.copy(transport = " VyAnDeX ")

        assertTrue(raw.isValid())
        assertEquals(config, OpenFluxProfileConfig.parse(raw.toJson()))
        assertEquals(config, OpenFluxProfileConfig.parse(raw.toUri()))
        assertEquals("yandex", CONFIG.transport)
        assertEquals(listOf("yandex", "vyandex"), OpenFluxProfileConfig.supportedTransports)
    }

    @Test
    fun volgaEngineConfigurationPreservesTransportAndRequiresEncryptionForBothRoles() {
        val config = CONFIG.copy(transport = "vyandex")
        listOf("client", "server").forEach { mode ->
            val runtime = Json.parseToJsonElement(config.toEngineJson(mode = mode)).jsonObject
            assertEquals("vyandex", runtime.getValue("transport").jsonPrimitive.content)
            assertEquals(DOCUMENT_URL, runtime.getValue("document_url").jsonPrimitive.content)
            assertEquals(KEY, runtime.getValue("encryption_key").jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> {
                config.copy(encryptionKey = "").toEngineJson(mode = mode)
            }
            assertFailsWith<IllegalArgumentException> {
                config.copy(documentUrl = "https://example.invalid/document").toEngineJson(mode = mode)
            }
        }
    }

    @Test
    fun normalizationPreservesTheDocumentAndCanonicalizesKeyAndTransport() {
        val input = OpenFluxProfileConfig(
            documentUrl = "  $DOCUMENT_URL \n",
            encryptionKey = " ${KEY.uppercase()}\t",
            transport = " YanDeX "
        )

        assertEquals(CONFIG, input.normalized())
        assertTrue(input.isValid())
        assertEquals(CONFIG, OpenFluxProfileConfig.parse(input.toJson()))
    }

    @Test
    fun canonicalProfileJsonAndUriRoundTrip() {
        val json = CONFIG.toJson()
        val uri = CONFIG.toUri()

        assertEquals(CONFIG, OpenFluxProfileConfig.parse(json))
        assertEquals(CONFIG, OpenFluxProfileConfig.parse(uri))
        assertEquals(CONFIG, OpenFluxProfileConfig.parse(" ${uri.replaceFirst("openflux", "OPENFLUX")}#Test "))
        assertTrue(uri.startsWith("openflux://"))
        assertFalse(uri.endsWith('='))
        assertFalse(DOCUMENT_URL in uri)
        assertFalse(KEY in uri)
        assertEquals(
            setOf("document_url", "encryption_key", "version", "transport"),
            Json.parseToJsonElement(json).jsonObject.keys
        )
    }

    @Test
    fun missingFieldsRemainEditableButNeverProduceAnEngineConfiguration() {
        val incomplete = assertNotNull(OpenFluxProfileConfig.parse("{}"))

        assertFalse(incomplete.isValid())
        assertFailsWith<IllegalArgumentException> { incomplete.toEngineJson() }
        assertFailsWith<IllegalArgumentException> { incomplete.toUri() }
        assertFalse(CONFIG.copy(documentUrl = "").isValid())
        assertFalse(CONFIG.copy(encryptionKey = "").isValid())
    }

    @Test
    fun anEncryptionKeyMustBeExactly32HexadecimalBytes() {
        listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "0x$KEY", "a ".repeat(32))
            .forEach { key ->
                val profile = CONFIG.copy(encryptionKey = key)
                assertFalse(profile.isValid(), "Invalid key length or alphabet was accepted")
                assertFailsWith<IllegalArgumentException> { profile.toEngineJson() }
            }

        assertTrue(CONFIG.copy(encryptionKey = KEY.uppercase()).isValid())
    }

    @Test
    fun onlyHttpsYandexDocumentHostsAndNonemptyPathsAreAccepted() {
        listOf(
            DOCUMENT_URL,
            "https://DOCS.YANDEX.RU/docs/view?url=synthetic-document",
            "https://docs.yandex.ru:443/docs/view?url=synthetic-document",
            "https://disk.yandex.ru/i/synthetic-document"
        ).forEach { url -> assertTrue(CONFIG.copy(documentUrl = url).isValid()) }

        listOf(
            "",
            "http://docs.yandex.ru/docs/view",
            "https://example.invalid/docs/view",
            "https://docs.yandex.ru.example.invalid/docs/view",
            "https://child.docs.yandex.ru/docs/view",
            "https://docs.yandex.ru:8443/docs/view",
            "https://user@docs.yandex.ru/docs/view",
            "https://user:password@docs.yandex.ru/docs/view",
            "https://docs.yandex.ru",
            "https://docs.yandex.ru/",
            "$DOCUMENT_URL#fragment",
            "https://docs.yandex.ru/docs/view bad",
            "https://docs.yandex.ru/docs/\nview",
            "https://docs.yandex.ru/" + "a".repeat(8192)
        ).forEach { url ->
            assertFalse(CONFIG.copy(documentUrl = url).isValid(), "Invalid document URL was accepted: $url")
        }
    }

    @Test
    fun unsupportedVersionsAndTransportsCannotStart() {
        listOf(CONFIG.copy(version = 0), CONFIG.copy(version = 2), CONFIG.copy(transport = "vk"),
            CONFIG.copy(transport = "google"), CONFIG.copy(transport = ""))
            .forEach { profile ->
                assertFalse(profile.isValid())
                assertFailsWith<IllegalArgumentException> { profile.toEngineJson() }
                assertFailsWith<IllegalArgumentException> { profile.toUri() }
                assertEquals(profile, OpenFluxProfileConfig.parse(profile.toJson()))
            }
    }

    @Test
    fun malformedAndUnknownProfileDataIsRejectedWithoutThrowing() {
        listOf(
            null,
            "",
            "  ",
            "not JSON",
            "[]",
            "{\"version\":\"one\"}",
            "{\"unknown_field\":true}",
            "openflux://",
            "openflux://%%%",
            "openflux://A",
            "x".repeat(32769)
        ).forEach { raw -> assertNull(OpenFluxProfileConfig.parse(raw)) }
    }

    @Test
    fun clientRuntimeJsonHasTheExactEncryptedLoopbackContract() {
        val runtime = Json.parseToJsonElement(
            CONFIG.toEngineJson(socksPort = 23841, socksUsername = "synthetic-user", socksPassword = "synthetic-pass")
        ).jsonObject

        assertEquals(
            setOf("version", "mode", "transport", "document_url", "encryption_key",
                "handshake_timeout_seconds", "socks5", "socks_username", "socks_password", "dns_server"),
            runtime.keys
        )
        assertEquals("1", runtime.getValue("version").jsonPrimitive.content)
        assertEquals("client", runtime.getValue("mode").jsonPrimitive.content)
        assertEquals("yandex", runtime.getValue("transport").jsonPrimitive.content)
        assertEquals(DOCUMENT_URL, runtime.getValue("document_url").jsonPrimitive.content)
        assertEquals(KEY, runtime.getValue("encryption_key").jsonPrimitive.content)
        assertEquals("60", runtime.getValue("handshake_timeout_seconds").jsonPrimitive.content)
        assertEquals("127.0.0.1:23841", runtime.getValue("socks5").jsonPrimitive.content)
        assertEquals("synthetic-user", runtime.getValue("socks_username").jsonPrimitive.content)
        assertEquals("synthetic-pass", runtime.getValue("socks_password").jsonPrimitive.content)
        assertEquals("1.1.1.1:53", runtime.getValue("dns_server").jsonPrimitive.content)
    }

    @Test
    fun serverRuntimeOmitsLocalProxyConfigurationButStillRequiresEncryption() {
        val runtime = Json.parseToJsonElement(CONFIG.toEngineJson(mode = "server")).jsonObject

        assertEquals(
            setOf("version", "mode", "transport", "document_url", "encryption_key", "handshake_timeout_seconds"),
            runtime.keys
        )
        assertEquals("server", runtime.getValue("mode").jsonPrimitive.content)
        assertEquals(KEY, runtime.getValue("encryption_key").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { CONFIG.copy(encryptionKey = "").toEngineJson(mode = "server") }
    }

    @Test
    fun runtimeOnlyFieldsCannotBeImportedAsAStoredProfile() {
        assertNull(OpenFluxProfileConfig.parse(CONFIG.toEngineJson()))
        assertNull(OpenFluxProfileConfig.parse(CONFIG.toEngineJson(mode = "server")))
    }

    @Test
    fun runtimeGenerationRejectsUnexpectedRolesAddressesAndPorts() {
        assertFailsWith<IllegalArgumentException> { CONFIG.toEngineJson(mode = "proxy") }
        listOf("0.0.0.0", "::1", "localhost", "192.0.2.1").forEach { host ->
            assertFailsWith<IllegalArgumentException> { CONFIG.toEngineJson(socksHost = host) }
        }
        listOf(-1, 0, 65536).forEach { port ->
            assertFailsWith<IllegalArgumentException> { CONFIG.toEngineJson(socksPort = port) }
        }
        listOf(1, 65535).forEach { port ->
            assertContains(CONFIG.toEngineJson(socksPort = port), "127.0.0.1:$port")
        }
    }

    @Test
    fun socksCredentialsMustBePairedAndFitTheWireByteLengths() {
        assertFailsWith<IllegalArgumentException> { CONFIG.toEngineJson(socksUsername = "only-user") }
        assertFailsWith<IllegalArgumentException> { CONFIG.toEngineJson(socksPassword = "only-password") }
        assertFailsWith<IllegalArgumentException> {
            CONFIG.toEngineJson(socksUsername = "a".repeat(256), socksPassword = "password")
        }
        assertFailsWith<IllegalArgumentException> {
            CONFIG.toEngineJson(socksUsername = "user", socksPassword = "\u00e9".repeat(128))
        }
        val runtime = Json.parseToJsonElement(
            CONFIG.toEngineJson(socksUsername = "a".repeat(255), socksPassword = "\u00e9".repeat(127))
        ).jsonObject
        assertEquals(255, runtime.getValue("socks_username").jsonPrimitive.content.encodeToByteArray().size)
        assertEquals(254, runtime.getValue("socks_password").jsonPrimitive.content.encodeToByteArray().size)
    }

    @Test
    fun runtimeJsonEscapesCredentialsWithoutChangingTheirValues() {
        val username = "synthetic\"user\\name"
        val password = "synthetic\npassword\tvalue"
        val runtime = Json.parseToJsonElement(
            CONFIG.toEngineJson(socksUsername = username, socksPassword = password)
        ).jsonObject

        assertEquals(username, runtime.getValue("socks_username").jsonPrimitive.content)
        assertEquals(password, runtime.getValue("socks_password").jsonPrimitive.content)
    }

    @Test
    fun profileCompletenessRequiresTheDocumentAndKeyEvenWithALocalProxy() {
        val profile = VpnProfileConfig(type = " OpenFlux ", localSocksHost = "127.0.0.1", localSocksPort = 23841)

        assertTrue(profile.isOpenFlux())
        assertEquals("OpenFlux", profile.typeLabel())
        assertFalse(profile.isCompleteFor())
        assertFalse(profile.copy(rawConfig = CONFIG.copy(encryptionKey = "").toJson()).isCompleteFor())
        assertTrue(profile.copy(rawConfig = CONFIG.toJson()).isCompleteFor())
        assertTrue(profile.copy(rawConfig = "  ", uri = CONFIG.toUri()).isCompleteFor())
    }

    @Test
    fun diagnosticStringDoesNotContainTheDocumentOrEncryptionKey() {
        val diagnostic = CONFIG.toString()

        assertFalse(DOCUMENT_URL in diagnostic)
        assertFalse(KEY in diagnostic)
        assertContains(diagnostic, "secrets=[redacted]")
        assertContains(diagnostic, "transport=yandex")
    }

    private companion object {
        const val DOCUMENT_URL = "https://docs.yandex.ru/docs/view?url=synthetic-openflux-document"
        const val KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val CONFIG = OpenFluxProfileConfig(documentUrl = DOCUMENT_URL, encryptionKey = KEY)
    }
}
