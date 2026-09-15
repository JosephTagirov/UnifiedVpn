package org.olcbox.app.ui.features.locations

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationOpenFluxEditorTest {
    @Test
    fun createsEncryptedOpenFluxProfileFromDedicatedFields() = runTest {
        withEditor { editor, source ->
            editor.startCreating(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onNameChanged("Yandex Docs")
            assertFalse(editor.isFormValid)

            editor.onLocalSocksPortChanged("70000")
            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL)
            editor.onOpenFluxEncryptionKeyChanged("AB".repeat(32))
            assertTrue(editor.isFormValid)
            var saved = false
            editor.saveEditing { saved = true }
            advanceUntilIdle()

            assertTrue(saved)
            val profile = assertNotNull(source.stored).locations.single().profile
            assertTrue(profile.isOpenFlux())
            assertEquals("Yandex Docs", profile.name)
            assertEquals(config(), OpenFluxProfileConfig.parse(profile.rawConfig))
            assertNull(profile.uri)
            assertNull(profile.localSocksHost)
            assertNull(profile.localSocksPort)
        }
    }

    @Test
    fun startingOpenFluxCreationClearsThePreviousEditorAndPreservesTheSavedProfile() = runTest {
        val original = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_OPENFLUX,
                name = "Original",
                uri = config().toUri()
            )
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(original))) { editor, source ->
            editor.startEditing("docs")
            editor.onNameChanged("")
            editor.onOpenFluxDocumentUrlChanged("")
            editor.onOpenFluxEncryptionKeyChanged("invalid")
            editor.onLocalSocksPortChanged("70000")
            assertNotNull(editor.nameError)
            assertNotNull(editor.openFluxEncryptionKeyError)

            editor.startCreating(VpnProfileConfig.TYPE_OPENFLUX)

            assertTrue(editor.isEditingOpenFlux)
            assertFalse(editor.isEditingOlcRtc)
            assertNull(editor.editingId)
            assertEquals("", editor.editingName)
            assertEquals(VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX), editor.editingProfile)
            assertEquals(OpenFluxProfileConfig(), editor.editingOpenFluxConfig)
            assertEquals("", editor.editingLocalSocksPort)
            assertNull(editor.nameError)
            assertNull(editor.profileError)
            assertNull(editor.openFluxDocumentUrlError)
            assertNull(editor.openFluxEncryptionKeyError)
            assertNull(editor.localSocksPortError)
            assertFalse(editor.isFormValid)

            editor.onNameChanged("New Docs")
            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL)
            editor.onOpenFluxEncryptionKeyChanged("cd".repeat(32))
            editor.saveEditing {}
            advanceUntilIdle()

            val locations = assertNotNull(source.stored).locations
            assertEquals(2, locations.size)
            val savedOriginal = locations.single { it.storageId == "docs" }
            assertEquals("Original", savedOriginal.displayName())
            assertEquals(config(), OpenFluxProfileConfig.parse(savedOriginal.profile.uri))
            val created = locations.single { it.storageId != "docs" }
            assertEquals("New Docs", created.displayName())
            assertEquals("cd".repeat(32), OpenFluxProfileConfig.parse(created.profile.rawConfig)?.encryptionKey)
        }
    }

    @Test
    fun genericCreationStillStartsWithAFreshOlcRtcProfile() = runTest {
        withEditor { editor, _ ->
            editor.startCreating(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onNameChanged("Docs")
            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL)
            editor.onOpenFluxEncryptionKeyChanged(config().encryptionKey)

            editor.startEditing(null)

            assertTrue(editor.isEditingOlcRtc)
            assertFalse(editor.isEditingOpenFlux)
            assertNull(editor.editingId)
            assertEquals("", editor.editingName)
            assertEquals(OpenFluxProfileConfig(), editor.editingOpenFluxConfig)
            assertFalse(editor.isFormValid)
        }
    }

    @Test
    fun refusesInvalidDocumentAndEncryptionKeyEvenWithLocalSocksConfigured() = runTest {
        withEditor { editor, source ->
            editor.startEditing(null)
            editor.onNameChanged("Invalid profile")
            editor.onProfileTypeChanged(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onLocalSocksPortChanged("11980")
            editor.onOpenFluxDocumentUrlChanged("https://example.invalid/document")
            editor.onOpenFluxEncryptionKeyChanged(config().encryptionKey)
            assertNotNull(editor.openFluxDocumentUrlError)
            assertFalse(editor.isFormValid)

            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL)
            editor.onOpenFluxEncryptionKeyChanged("short-key")
            assertNotNull(editor.openFluxEncryptionKeyError)
            assertFalse(editor.isFormValid)
            var saved = false
            editor.saveEditing { saved = true }
            advanceUntilIdle()

            assertFalse(saved)
            assertTrue(source.stored?.locations.isNullOrEmpty())
        }
    }

    @Test
    fun editingImportedUriReplacesStaleSerializedCredentials() = runTest {
        val entry = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_OPENFLUX,
                name = "Original",
                uri = config().toUri()
            )
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(entry))) { editor, source ->
            editor.startEditing("docs")
            assertEquals(config(), editor.editingOpenFluxConfig)
            assertTrue(editor.isFormValid)
            editor.onNameChanged("Renamed")
            editor.onOpenFluxEncryptionKeyChanged("cd".repeat(32))
            editor.saveEditing {}
            advanceUntilIdle()

            val saved = assertNotNull(source.stored).locations.single()
            assertEquals("docs", saved.storageId)
            assertEquals("Renamed", saved.displayName())
            assertNull(saved.profile.uri)
            assertEquals("cd".repeat(32), OpenFluxProfileConfig.parse(saved.profile.rawConfig)?.encryptionKey)
        }
    }

    @Test
    fun protocolSwitchKeepsTheOpenFluxDraftSeparate() = runTest {
        withEditor { editor, _ ->
            editor.startEditing(null)
            editor.onNameChanged("Docs")
            editor.onProfileTypeChanged(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL)
            editor.onOpenFluxEncryptionKeyChanged(config().encryptionKey)
            editor.onProfileTypeChanged(VpnProfileConfig.TYPE_VLESS)
            editor.onProfileUriChanged("vless://test@example.invalid:443")
            editor.onProfileTypeChanged(VpnProfileConfig.TYPE_OPENFLUX)

            assertEquals(config(), editor.editingOpenFluxConfig)
            assertTrue(editor.isFormValid)
        }
    }

    private suspend fun TestScope.withEditor(
        bundle: LocationBundleV4? = null,
        test: suspend (LocationViewModel, FakeLocationsDataSource) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeLocationsDataSource(bundle)
        val editor = LocationViewModel(LocationsRepositoryImpl(source))
        try {
            runCurrent()
            test(editor, source)
        } finally {
            editor.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun config() = OpenFluxProfileConfig(
        documentUrl = DOCUMENT_URL,
        encryptionKey = "ab".repeat(32)
    )

    private class FakeLocationsDataSource(var stored: LocationBundleV4?) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private companion object {
        const val DOCUMENT_URL = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Ftest-document"
    }
}
