package org.olcbox.app.ui.features.locations

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.VpnPingUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationOpenFluxEditorTest {
    @Test
    fun creatingVolgaProfileRequiresAnExplicitTransportSelection() = runTest {
        withEditor { editor, source ->
            editor.startCreating(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onNameChanged("Volga")
            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL)
            editor.onOpenFluxEncryptionKeyChanged(config().encryptionKey)
            assertEquals("yandex", editor.editingOpenFluxConfig.transport)

            editor.onOpenFluxTransportChanged(" VyAnDeX ")

            assertEquals(config().copy(transport = "vyandex"), editor.editingOpenFluxConfig)
            assertTrue(editor.isFormValid)
            assertNull(editor.openFluxTransportError)
            editor.saveEditing {}
            advanceUntilIdle()
            val saved = assertNotNull(source.stored).locations.single()
            assertEquals(config().copy(transport = "vyandex"), OpenFluxProfileConfig.parse(saved.profile.rawConfig))
        }
    }

    @Test
    fun editingImportedVolgaProfilePreservesTransportThroughNameKeyAndDocumentChanges() = runTest {
        val config = config().copy(transport = "vyandex")
        val original = LocationEntry.fromProfile(
            storageId = "volga",
            profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX, name = "Original", uri = config.toUri())
        )
        withEditor(LocationBundleV4(activeLocationId = "volga", locations = listOf(original))) { editor, source ->
            editor.startEditing("volga")
            assertEquals(config, editor.editingOpenFluxConfig)
            editor.onNameChanged("Renamed")
            editor.onOpenFluxEncryptionKeyChanged("cd".repeat(32))
            editor.onOpenFluxDocumentUrlChanged(DOCUMENT_URL + "-new")
            editor.saveEditing {}
            advanceUntilIdle()

            val saved = assertNotNull(source.stored).locations.single()
            assertEquals("Renamed", saved.displayName())
            assertNull(saved.profile.uri)
            assertEquals(config.copy(documentUrl = DOCUMENT_URL + "-new", encryptionKey = "cd".repeat(32)),
                OpenFluxProfileConfig.parse(saved.profile.rawConfig))
            editor.startEditing("volga")
            assertEquals("vyandex", editor.editingOpenFluxConfig.transport)
        }
    }

    @Test
    fun selectingTransportPreservesRawDraftFieldsAndProtocolSwitchDoesNotResetIt() = runTest {
        withEditor { editor, _ ->
            editor.startCreating(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onNameChanged("Volga")
            val url = " $DOCUMENT_URL "
            val key = " ${config().encryptionKey.uppercase()} "
            editor.onOpenFluxDocumentUrlChanged(url)
            editor.onOpenFluxEncryptionKeyChanged(key)

            editor.onOpenFluxTransportChanged("vyandex")
            editor.onProfileTypeChanged(VpnProfileConfig.TYPE_VLESS)
            editor.onProfileUriChanged("vless://test@example.invalid:443")
            editor.onProfileTypeChanged(VpnProfileConfig.TYPE_OPENFLUX)

            assertEquals("vyandex", editor.editingOpenFluxConfig.transport)
            assertEquals(url, editor.editingOpenFluxConfig.documentUrl)
            assertEquals(key, editor.editingOpenFluxConfig.encryptionKey)
            editor.onOpenFluxTransportChanged("yandex")
            assertEquals("yandex", editor.editingOpenFluxConfig.transport)
            assertEquals(url, editor.editingOpenFluxConfig.documentUrl)
            assertEquals(key, editor.editingOpenFluxConfig.encryptionKey)
        }
    }

    @Test
    fun unknownTransportIsNeverSilentlyChangedAndCannotBeSaved() = runTest {
        withEditor { editor, source ->
            editor.startCreating(VpnProfileConfig.TYPE_OPENFLUX)
            editor.onNameChanged("Invalid transport")
            editor.onProfileRawConfigChanged(config().copy(transport = "unsupported").toJson())

            assertEquals("unsupported", editor.editingOpenFluxConfig.transport)
            assertEquals("Unsupported OpenFlux transport", editor.openFluxTransportError)
            assertEquals(editor.openFluxTransportError, editor.profileError)
            assertFalse(editor.isFormValid)
            var saved = false
            editor.saveEditing { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertTrue(source.stored?.locations.isNullOrEmpty())

            editor.onOpenFluxTransportChanged("vyandex")
            assertNull(editor.openFluxTransportError)
            assertNull(editor.profileError)
            assertTrue(editor.isFormValid)
        }
    }

    @Test
    fun editingTargetCancelsOldPingWithoutRemovingOrOverwritingItsReplacement() = runTest {
        val entry = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX, uri = config().toUri())
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(entry))) { editor, source ->
            val oldResult = CompletableDeferred<Long?>()
            val newResult = CompletableDeferred<Long?>()
            editor.refreshPings(performPing = { _, _ -> withContext(NonCancellable) { oldResult.await() } })
            runCurrent()

            val replacement = LocationEntry.fromProfile(
                storageId = "docs",
                profile = entry.profile.copy(uri = config().copy(encryptionKey = "cd".repeat(32)).toUri())
            )
            source.stored = source.stored?.copy(locations = listOf(replacement))
            editor.loadLocations()
            runCurrent()
            val invalidated = editor.pingsState as PingsState.Success
            assertTrue(invalidated.pings.isEmpty())

            editor.refreshPings(performPing = { _, _ -> newResult.await() })
            runCurrent()
            assertTrue("docs" in (editor.pingsState as PingsState.Loading).pendingLocationIds)
            oldResult.complete(555)
            runCurrent()
            assertTrue("docs" in (editor.pingsState as PingsState.Loading).pendingLocationIds)

            newResult.complete(42)
            advanceUntilIdle()
            assertEquals(42, (editor.pingsState as PingsState.Success).pings["docs"])
        }
    }

    @Test
    fun profileConversionClearsUnavailableHintAndRemovalClearsCachedPing() = runTest {
        val entry = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX, uri = config().toUri())
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(entry))) { editor, source ->
            editor.refreshPings(performPing = { _, _ -> throw VpnPingUnavailableException() })
            advanceUntilIdle()
            assertTrue("docs" in editor.pingsState.unavailable)

            val replacement = LocationEntry.fromProfile(
                storageId = "docs",
                profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_VLESS, uri = "vless://test@example.invalid:443")
            )
            source.stored = source.stored?.copy(locations = listOf(replacement))
            editor.loadLocations()
            advanceUntilIdle()
            assertTrue(editor.pingsState.unavailable.isEmpty())
            editor.refreshPings(performPing = { _, _ -> 42 })
            advanceUntilIdle()
            assertEquals(42, (editor.pingsState as PingsState.Success).pings["docs"])

            source.stored = source.stored?.copy(locations = emptyList(), activeLocationId = null)
            editor.loadLocations()
            advanceUntilIdle()
            assertTrue((editor.pingsState as PingsState.Success).pings.isEmpty())
            assertTrue(editor.pingsState.unavailable.isEmpty())
        }
    }

    @Test
    fun renameAndEquivalentOpenFluxSerializationPreserveMeasuredLatency() = runTest {
        val entry = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX, uri = config().toUri())
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(entry))) { editor, source ->
            editor.refreshPings(performPing = { _, _ -> 42 })
            advanceUntilIdle()
            source.stored = source.stored?.copy(locations = listOf(
                LocationEntry.fromProfile(
                    storageId = "docs",
                    profile = entry.profile.copy(name = "Renamed", uri = null, rawConfig = config().toJson())
                )
            ))
            editor.loadLocations()
            advanceUntilIdle()
            assertEquals("Renamed", editor.locations.single().fullName)
            assertEquals(42, (editor.pingsState as PingsState.Success).pings["docs"])
        }
    }

    @Test
    fun unavailableOpenFluxPingIsNotStoredAsOfflineAndCanLaterSucceed() = runTest {
        val entry = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX, uri = config().toUri())
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(entry))) { editor, _ ->
            var completedCount: Pair<Int, Int>? = null
            editor.refreshPings(
                performPing = { _, _ -> throw VpnPingUnavailableException() },
                onComplete = { online, total -> completedCount = online to total }
            )
            advanceUntilIdle()
            val unavailable = editor.pingsState as PingsState.Success
            assertFalse(unavailable.pings.containsKey("docs"))
            assertEquals("Connect to this profile to check latency", unavailable.unavailable["docs"])
            assertEquals(0 to 0, completedCount)

            editor.refreshPings(performPing = { _, _ -> 42 })
            advanceUntilIdle()
            val connected = editor.pingsState as PingsState.Success
            assertEquals(42, connected.pings["docs"])
            assertTrue(connected.unavailable.isEmpty())
        }
    }

    @Test
    fun genuinelyFailedConnectedPingClearsConnectFirstHintAndIsOffline() = runTest {
        val entry = LocationEntry.fromProfile(
            storageId = "docs",
            profile = VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX, uri = config().toUri())
        )
        withEditor(LocationBundleV4(activeLocationId = "docs", locations = listOf(entry))) { editor, _ ->
            editor.refreshPings(performPing = { _, _ -> throw VpnPingUnavailableException() })
            advanceUntilIdle()
            editor.refreshPings(performPing = { _, _ -> null })
            advanceUntilIdle()
            val failed = editor.pingsState as PingsState.Success
            assertTrue(failed.pings.containsKey("docs"))
            assertNull(failed.pings["docs"])
            assertTrue(failed.unavailable.isEmpty())
        }
    }

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
