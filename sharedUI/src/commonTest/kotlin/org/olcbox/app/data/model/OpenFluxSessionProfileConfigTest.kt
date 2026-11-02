package org.olcbox.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxSessionProfileConfigTest {
    private val room = "https://telemost.yandex.ru/j/12345678901234"
    private val document = "https://cloud.mail.ru/public/TestRoom/TestDocument"
    private val secret = "Ab12".repeat(16)

    private fun profile(type: String = "telemost", url: String = room) = OpenFluxSessionProfileConfig(
        name = "Test", negotiate = true, codec = "batched", secret = secret,
        context = url, transports = listOf(OpenFluxSessionTransport(type = type, url = url))
    )


    @Test fun directSessionRetainsExplicitContextAndRejectsPrivateTargets() {
        val endpoint = "151.243.209.234:50000"
        val direct = OpenFluxSessionProfileConfig(name="Direct", negotiate=true, codec="batched", secret=secret,
            context="tcp://$endpoint", transports=listOf(OpenFluxSessionTransport(type="direct",dial=endpoint,priority=50)))
        assertTrue(direct.isValid())
        val imported = assertNotNull(OpenFluxProfileConfig.parse(direct.toUri()))
        assertTrue(imported.isValid())
        assertEquals(direct.copy(name=""), imported.toSessionConfig())
        assertEquals(imported, OpenFluxProfileConfig.parse(imported.toUri()))
        assertTrue(direct.toEngineConf("key","cookies",1080).contains("Dial = $endpoint"))
        for (bad in listOf("127.0.0.1:1024","10.0.0.1:5555","169.254.169.254:1024","151.243.209.234:22","example.com:4444","151.243.209.234:50000\nDebug=1")) {
            assertFalse(validPublicDirectEndpoint(bad))
        }
        assertFalse(direct.copy(context="other").isValid())
        assertFalse(direct.copy(secret="").isValid())
    }

    @Test
    fun importsFixtureProducedByUpstream050ShareMake() {
        // Generated offline by the pinned Go share.Make with the synthetic room and key above.
        val link = "openflux://v1/pM49CsJAFATgu0y9ZE3838472InFGh_-kOyG9yaQEHJ3SeEBxGJgmvmYCSm2goCzGOGQ5JH5ihQEai8OJrUKEXC6ldU_gUOdE2VYsCfZWfCe0kibjcUY012GQnv_9mW13mx3-8NxtTQ4UGOyLisN4TKBY7c8_m7h0GvzIzpf508AAAD__w"
        assertEquals(profile().copy(codec = ""), OpenFluxSessionProfileConfig.parseLink(link))
    }

    @Test
    fun sharedLinksRoundTripAndPreserveTheCaseSensitiveSessionSecret() {
        for (original in listOf(profile(), profile("mailru", document))) {
            val link = original.toUri()
            assertTrue(link.startsWith(OpenFluxSessionProfileConfig.LINK_PREFIX))
            assertEquals(original, OpenFluxSessionProfileConfig.parseLink(link))
            val unified = assertNotNull(OpenFluxProfileConfig.parse(link))
            assertTrue(unified.isSession)
            assertTrue(unified.isValid())
            assertEquals(secret, unified.normalized().encryptionKey)
            assertEquals(unified, OpenFluxProfileConfig.parse(unified.toJson()))
            assertEquals(unified, OpenFluxProfileConfig.parse(unified.toUri()))
            assertFalse(unified.copy(version = 1).isValid())
            assertFalse(unified.copy(wireCodec = OpenFluxProfileConfig.CODEC_LEGACY).isValid())
        }
    }

    @Test
    fun invalidCompressedLinksCannotBecomeLegacyOrUnencryptedProfiles() {
        val prefix = OpenFluxSessionProfileConfig.LINK_PREFIX
        for (suffix in listOf("", "not-a-deflate-stream", "a".repeat(32769), "%00", "!!!!")) {
            assertNull(OpenFluxSessionProfileConfig.parseLink(prefix + suffix))
            assertNull(OpenFluxProfileConfig.parse(prefix + suffix))
        }
        val unsecured = Json.encodeToString(profile().copy(negotiate = false))
        assertNull(OpenFluxSessionProfileConfig.parseLink(prefix + encodeOpenFluxSessionPayload(unsecured)))
        assertNull(OpenFluxSessionProfileConfig.parseLink(profile().toUri() + "#name"))
    }

    @Test
    fun acceptsOnlySeparateEncryptedSessionProfiles() {
        assertTrue(profile().isValid())
        assertTrue(profile("mailru", document).isValid())
        assertEquals(profile(), OpenFluxSessionProfileConfig.parseCoreConfig(Json.encodeToString(profile())))
        assertEquals(secret, OpenFluxSessionProfileConfig.parseCoreConfig(Json.encodeToString(profile()))?.secret)
        assertNull(OpenFluxProfileConfig.parse(Json.encodeToString(profile())))
    }

    @Test
    fun rejectsDowngradesAndMissingSecrets() {
        for (value in listOf(
            profile().copy(negotiate = false), profile().copy(secret = ""),
            profile().copy(secret = "a".repeat(63)), profile().copy(secret = "z".repeat(64)),
            profile().copy(codec = "legacy"), profile().copy(mode = "stream"),
            profile().copy(context = ""), profile().copy(context = room + "/"),
            profile().copy(transports = emptyList()),
            profile().copy(transports = profile().transports + profile("mailru", document).transports)
        )) {
            assertFalse(value.isValid())
            assertNull(OpenFluxSessionProfileConfig.parseCoreConfig(Json.encodeToString(value)))
        }
    }

    @Test
    fun rejectsInjectedAndForeignTransportAddresses() {
        val invalid = listOf(
            "http://telemost.yandex.ru/j/12345678901234",
            "https://telemost.yandex.ru.evil.invalid/j/12345678901234",
            "https://user:pass@telemost.yandex.ru/j/12345678901234",
            "https://telemost.yandex.ru:444/j/12345678901234",
            "$room#fragment", "$room?query=1", "$room;ignored", "$room\nSensitive=true",
            "https://telemost.yandex.ru/j/123", "https://127.0.0.1/j/12345678901234"
        )
        for (url in invalid) assertFalse(profile(url = url).isValid())
        assertFalse(profile("mailru", room).isValid())
        assertFalse(profile("telemost", document).isValid())
        assertFalse(profile("direct", room).isValid())
        assertFalse(profile().copy(transports = listOf(profile().transports.single().copy(dial = "127.0.0.1:22"))).isValid())
    }

    @Test
    fun telemostCannotClaimDesktopTunSupport() {
        assertFalse(profile().supportsDesktopTun)
        assertTrue(profile("mailru", document).supportsDesktopTun)
        assertFalse(profile("mailru", document).copy(secret = "").supportsDesktopTun)
    }

    @Test
    fun configUsesLoopbackAndPrivateKeyFileAndStrictArgument() {
        val config = profile().toEngineConf("C:/private/key", "C:/private/cookies.json", 18951)
        assertTrue(config.contains("Socks5 = 127.0.0.1:18951"))
        assertTrue(config.contains("EncryptionKeyFile = C:/private/key"))
        assertTrue(config.contains("SessionContext = $room"))
        assertTrue(config.contains("Sensitive = false"))
        assertTrue(config.contains("[Transport \"telemost\"]"))
        assertFalse(config.contains(secret))
        assertEquals(listOf("--config", "C:/private/client.conf", "--negotiate"),
            OpenFluxSessionProfileConfig.engineArguments("C:/private/client.conf"))
        for (path in listOf("", " key", "key\nSensitive=true", "key#ignored", "key;ignored")) {
            assertFailsWith<IllegalArgumentException> { profile().toEngineConf(path, "cookies", 18951) }
            assertFailsWith<IllegalArgumentException> { OpenFluxSessionProfileConfig.engineArguments(path) }
        }
        assertFailsWith<IllegalArgumentException> { profile().toEngineConf("key", "cookies", 0) }
    }

    @Test
    fun strictParserAndDiagnosticsDoNotExposeSecrets() {
        assertFalse(profile().toString().contains(secret))
        assertFalse(profile().toString().contains(room))
        assertFalse(profile().transports.single().toString().contains(room))
        assertNull(OpenFluxSessionProfileConfig.parseCoreConfig("x".repeat(16385)))
        assertNull(OpenFluxSessionProfileConfig.parseCoreConfig("not json"))
        val json = Json.encodeToString(profile())
        assertNotNull(OpenFluxSessionProfileConfig.parseCoreConfig(json))
        assertNull(OpenFluxSessionProfileConfig.parseCoreConfig(json.dropLast(1) + ",\"extra\":true}"))
    }
}
