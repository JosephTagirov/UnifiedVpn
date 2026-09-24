package org.olcbox.app.ui.features.home

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationProfileSelectionSyncTest {
    @Test
    fun serviceSelectionUpdatesBothOpenScreensWithoutStartingAnotherConnection() = runTest {
        withScreens { fixture ->
            assertSelection(fixture, FIRST)

            fixture.repository.setActiveLocationId(SECOND)
            runCurrent()

            assertSelection(fixture, SECOND)
            assertTrue(fixture.home.state.value.isVpnConnected)
            assertEquals(0, fixture.vpn.startCalls)
            assertEquals(0, fixture.vpn.stopCalls)
        }
    }

    @Test
    fun serviceRollbackRestoresBothScreensToThePreviousProfile() = runTest {
        withScreens { fixture ->
            fixture.repository.setActiveLocationId(SECOND)
            runCurrent()
            assertSelection(fixture, SECOND)

            fixture.repository.setActiveLocationId(FIRST)
            runCurrent()

            assertSelection(fixture, FIRST)
            assertEquals(FIRST, fixture.repository.getActiveLocationId())
            assertEquals(0, fixture.vpn.startCalls)
        }
    }

    @Test
    fun foregroundRereadsSelectionEvenWhenItsChangeWasNotObserved() = runTest {
        withScreens { fixture ->
            fixture.source.stored = fixture.source.stored.copy(activeLocationId = SECOND)
            runCurrent()
            assertEquals(FIRST, fixture.home.state.value.selectedLocation?.storageId)

            fixture.home.onForeground()
            runCurrent()

            assertEquals(SECOND, fixture.home.state.value.selectedLocation?.storageId)
            assertEquals(SECOND, fixture.home.state.value.configData.name)
            assertEquals(0, fixture.vpn.startCalls)
        }
    }

    @Test
    fun delayedOldReadCannotOverwriteANewerServiceSelection() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        var delayNextRead = false
        withScreens(wrapRepository = { delegate ->
            object : LocationsRepository by delegate {
                override suspend fun getActiveLocation(): LocationEntry? {
                    val snapshot = delegate.getActiveLocation()
                    if (delayNextRead) {
                        delayNextRead = false
                        readStarted.complete(Unit)
                        releaseOldRead.await()
                    }
                    return snapshot
                }
            }
        }) { fixture ->
            delayNextRead = true
            fixture.home.loadCurrentConfig()
            runCurrent()
            assertTrue(readStarted.isCompleted)

            fixture.repository.setActiveLocationId(SECOND)
            runCurrent()
            assertSelection(fixture, SECOND)

            releaseOldRead.complete(Unit)
            runCurrent()

            assertSelection(fixture, SECOND)
        }
    }

    @Test
    fun homeObservesASelectionChangedDuringItsInitialRead() = runTest {
        withScreens(wrapRepository = { delegate ->
            object : LocationsRepository by delegate {
                private var firstRead = true

                override suspend fun getActiveLocation(): LocationEntry? {
                    val snapshot = delegate.getActiveLocation()
                    if (firstRead) {
                        firstRead = false
                        delegate.setActiveLocationId(SECOND)
                    }
                    return snapshot
                }
            }
        }) { fixture ->
            assertSelection(fixture, SECOND)
        }
    }

    @Test
    fun profileListObservesASelectionChangedDuringItsInitialRead() = runTest {
        withScreens(wrapRepository = { delegate ->
            object : LocationsRepository by delegate {
                private var firstRead = true

                override suspend fun getBundle(): LocationBundleV4 {
                    val snapshot = delegate.getBundle()
                    if (firstRead) {
                        firstRead = false
                        delegate.setActiveLocationId(SECOND)
                    }
                    return snapshot
                }
            }
        }) { fixture ->
            assertSelection(fixture, SECOND)
        }
    }

    @Test
    fun clearedSelectionDoesNotLeaveOldProfileOnEitherScreen() = runTest {
        withScreens { fixture ->
            fixture.repository.saveBundle(LocationBundleV4())
            runCurrent()

            assertNull(fixture.home.state.value.selectedLocation)
            assertNull(fixture.home.state.value.activeProfile)
            assertFalse(fixture.home.state.value.canStartVpn)
            assertNull(fixture.locations.selectedLocationId)
            assertTrue(fixture.locations.locations.isEmpty())
        }
    }

    private suspend fun TestScope.withScreens(
        wrapRepository: (LocationsRepository) -> LocationsRepository = { it },
        test: suspend (Fixture) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MemoryLocationsDataSource(
            LocationBundleV4(
                activeLocationId = FIRST,
                locations = listOf(profile(FIRST), profile(SECOND))
            )
        )
        val http = HttpClient(MockEngine { error("No network requests are allowed in selection tests") })
        val base = LocationsRepositoryImpl(source, httpClient = http)
        val offline = object : LocationsRepository by base {
            override suspend fun refreshDueSubscriptions(subscriptionProxy: SubscriptionFetchProxy?): Int = 0
            override suspend fun nextSubscriptionRefreshAtEpochMs(): Long? = null
        }
        val repository = wrapRepository(offline)
        val vpn = FakeVpnManager()
        val home = HomeScreenViewModel(vpn, repository, NoConfigImporter, NoLogExporter)
        val locations = LocationViewModel(repository)
        try {
            runCurrent()
            test(Fixture(source, repository, vpn, home, locations))
        } finally {
            home.viewModelScope.cancel()
            locations.viewModelScope.cancel()
            http.close()
            Dispatchers.resetMain()
        }
    }

    private fun assertSelection(fixture: Fixture, expected: String) {
        assertEquals(expected, fixture.home.state.value.selectedLocation?.storageId)
        assertEquals(expected, fixture.home.state.value.configData.name)
        assertEquals(expected, fixture.locations.selectedLocationId)
        assertTrue(fixture.home.state.value.canStartVpn)
    }

    private fun profile(id: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(
            name = id,
            id = "https://example.invalid/$id",
            key = "ab".repeat(32),
            bypassProvider = LocationConfig.PROVIDER_JITSI,
            transport = LocationConfig.TRANSPORT_DATACHANNEL
        )
    )

    private data class Fixture(
        val source: MemoryLocationsDataSource,
        val repository: LocationsRepository,
        val vpn: FakeVpnManager,
        val home: HomeScreenViewModel,
        val locations: LocationViewModel
    )

    private class MemoryLocationsDataSource(var stored: LocationBundleV4) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4 = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private class FakeVpnManager : VpnManager {
        override val status = MutableStateFlow<VpnStatus>(VpnStatus.Connected)
        override val logs = MutableStateFlow<List<String>>(emptyList())
        override val isConnected = MutableStateFlow(true)
        var startCalls = 0
        var stopCalls = 0

        override fun needsPermission(): Boolean = false
        override fun startVpn() { startCalls++ }
        override fun stopVpn() { stopCalls++ }
        override suspend fun ping(locationConfig: LocationConfig, profile: VpnProfileConfig): Long? = null
        override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
    }

    private object NoConfigImporter : ConfigImporter {
        override fun getFromClipboard(): String? = error("Unexpected clipboard read")
        override fun copyToClipboard(text: String) = error("Unexpected clipboard write")
        override suspend fun readTextFromSource(source: Any): String? = error("Unexpected config import")
    }

    private object NoLogExporter : LogExporter {
        override suspend fun writeLogs(target: Any, content: String): Result<String> =
            error("Unexpected log export")
    }

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
    }
}
