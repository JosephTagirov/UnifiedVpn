package org.olcbox.app.data.share

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigShareServiceTest {
    @Test
    fun volgaShareAndImportPreserveTransportAndCredentials() = runTest {
        val config = fixtureConfig().copy(transport = "vyandex")
        val uri = assertNotNull(ConfigShareService.openFluxUri(config, "Volga device"))
        val imported = importSharedProfile(uri)

        assertTrue(imported.isComplete())
        assertEquals("Volga device", imported.displayName())
        assertEquals(config, OpenFluxProfileConfig.parse(imported.profile.rawConfig))
        assertEquals(config, OpenFluxProfileConfig.parse(imported.profile.uri))
    }

    @Test
    fun openFluxSharePreservesConfigAndUnicodeDisplayNameOnImport() = runTest {
        val config = fixtureConfig()
        val name = "Телефон + #1 / 50%"
        val uri = assertNotNull(ConfigShareService.openFluxUri(config, name))
        assertTrue(uri.startsWith("openflux://"))
        assertTrue("%20" in uri.substringAfter('#'))
        assertFalse('+' in uri.substringAfter('#'))
        val imported = importSharedProfile(uri)

        assertEquals(VpnProfileConfig.TYPE_OPENFLUX, imported.profile.normalizedType)
        assertEquals(name, imported.displayName())
        assertEquals(config, OpenFluxProfileConfig.parse(imported.profile.rawConfig))
    }

    @Test
    fun openFluxShareNormalizesKeyAndSurroundingWhitespace() {
        val config = fixtureConfig()
        val raw = config.copy(documentUrl = " ${config.documentUrl} ", encryptionKey = " ${config.encryptionKey.uppercase()} ")
        val uri = assertNotNull(ConfigShareService.openFluxUri(raw, " Client "))

        assertEquals(config, OpenFluxProfileConfig.parse(uri))
        assertEquals("Client", uri.substringAfter('#'))
    }

    @Test
    fun openFluxShareUsesDefaultNameWhenBlank() = runTest {
        val uri = assertNotNull(ConfigShareService.openFluxUri(fixtureConfig(), " "))
        assertEquals("OpenFlux", importSharedProfile(uri).displayName())
    }

    @Test
    fun openFluxShareRefusesInvalidOrIncompleteProfiles() {
        val valid = fixtureConfig()
        listOf(
            OpenFluxProfileConfig(),
            valid.copy(documentUrl = ""),
            valid.copy(documentUrl = "https://example.invalid/document"),
            valid.copy(documentUrl = "http://docs.yandex.ru/docs/view?url=fixture"),
            valid.copy(documentUrl = "https://user:secret@docs.yandex.ru/docs/view?url=fixture"),
            valid.copy(documentUrl = "https://docs.yandex.ru:8443/docs/view?url=fixture"),
            valid.copy(encryptionKey = ""),
            valid.copy(encryptionKey = "ab".repeat(31)),
            valid.copy(encryptionKey = "z".repeat(64)),
            valid.copy(version = 2),
            valid.copy(transport = "unsupported")
        ).forEach { assertNull(ConfigShareService.openFluxUri(it, "Client")) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun openFluxShareContainsOnlyFourClientFieldsAndNameFragment() {
        val uri = assertNotNull(ConfigShareService.openFluxUri(fixtureConfig(), "Client"))
        val payload = uri.substringAfter("://").substringBefore('#')
        val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        val json = Json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()).jsonObject

        assertEquals(setOf("document_url", "encryption_key", "version", "transport"), json.keys)
        assertEquals("Client", uri.substringAfter('#'))
    }

    @Test
    fun openFluxShareKeepsUrlEscapingUnchanged() {
        val config = fixtureConfig().copy(
            documentUrl = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Ffixture%3Fid%3D42&token=fixture"
        )
        val uri = assertNotNull(ConfigShareService.openFluxUri(config, "Client"))
        assertEquals(config.documentUrl, assertNotNull(OpenFluxProfileConfig.parse(uri)).documentUrl)
    }

    @Test
    fun openFluxShareNameCannotInjectAnotherProfileLine() = runTest {
        val name = "Client\n#openflux://not-another-profile"
        val uri = assertNotNull(ConfigShareService.openFluxUri(fixtureConfig(), name))
        assertFalse('\n' in uri)
        assertFalse('\r' in uri)
        val imported = importSharedProfile(uri)
        assertEquals(fixtureConfig(), OpenFluxProfileConfig.parse(imported.profile.rawConfig))
        assertEquals(name, imported.displayName())
    }

    @Test
    fun openFluxShareRefusesOutputBeyondExistingParserLimit() {
        assertNull(ConfigShareService.openFluxUri(fixtureConfig(), "x".repeat(32768)))
    }

    private fun fixtureConfig() = OpenFluxProfileConfig(
        documentUrl = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Fsynthetic-share-fixture",
        encryptionKey = "ab".repeat(32)
    )

    private suspend fun importSharedProfile(uri: String): LocationEntry {
        val client = HttpClient(MockEngine { error("Profile import must not use the network") })
        return try {
            val repository = LocationsRepositoryImpl(MemorySource(), httpClient = client)
            assertTrue(repository.importText(uri))
            repository.getBundle().locations.single()
        } finally {
            client.close()
        }
    }

    private class MemorySource : LocationsDataSource {
        private var stored: LocationBundleV4? = null
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }
}
