package org.olcbox.app.ui.features.home

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.olcbox.app.vpn.ConnectionPreflight
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSubscriptionRefreshTest {
    @Test fun newProfilesAppearWithoutRestartingAnUnchangedConnection() = runTest {
        withHome { f ->
            f.refresh = {
                f.repository.saveBundle(f.repository.getBundle().let { it.copy(locations = it.locations + profile("new")) })
                1
            }
            f.home.refreshSubscriptions()
            assertTrue(f.home.state.value.isRefreshingSubscriptions)
            runCurrent()
            assertEquals(2, f.repository.getAllLocations().size)
            assertEquals("active", f.home.state.value.selectedLocation?.storageId)
            assertEquals(0, f.vpn.startCalls)
            assertFalse(f.home.state.value.isRefreshingSubscriptions)
            assertFalse(f.home.state.value.subscriptionRefreshFailed)
            assertEquals("Subscriptions updated: 1", f.home.state.value.subscriptionRefreshMessage)
        }
    }

    @Test fun changedActiveConfigIsLoadedAndReconnectedOnce() = runTest {
        withHome { f ->
            f.refresh = {
                f.repository.saveBundle(LocationBundleV4(activeLocationId = "active", locations = listOf(profile("active", "b"))))
                1
            }
            f.home.refreshSubscriptions()
            runCurrent()
            assertEquals("b".repeat(64), f.home.state.value.configData.key)
            assertEquals(1, f.vpn.startCalls)
            assertEquals(0, f.vpn.stopCalls)
        }
    }

    @Test fun removedActiveProfileStopsTheOldConnection() = runTest {
        withHome { f ->
            f.refresh = { f.repository.saveBundle(LocationBundleV4()); 1 }
            f.home.refreshSubscriptions()
            runCurrent()
            assertEquals(1, f.vpn.stopCalls)
            assertEquals(0, f.vpn.startCalls)
            assertNull(f.home.state.value.activeProfile)
            assertFalse(f.home.state.value.canStartVpn)
        }
    }

    @Test fun failedFetchKeepsProfilesAndExplainsTheFailure() = runTest {
        withHome { f ->
            f.refresh = { 0 }
            f.home.refreshSubscriptions()
            runCurrent()
            assertEquals(1, f.repository.getAllLocations().size)
            assertEquals(0, f.vpn.startCalls)
            assertTrue(f.home.state.value.subscriptionRefreshFailed)
            assertEquals("Could not update subscriptions. Saved profiles were kept.", f.home.state.value.subscriptionRefreshMessage)
            assertFalse(f.home.state.value.isRefreshingSubscriptions)
        }
    }

    @Test fun repeatedClicksDoNotStartConcurrentDownloadsAndFailuresReleaseTheButton() = runTest {
        withHome { f ->
            val gate = CompletableDeferred<Unit>()
            f.refresh = { gate.await(); error("Network unavailable") }
            f.home.refreshSubscriptions()
            f.home.refreshSubscriptions()
            runCurrent()
            assertEquals(1, f.refreshCalls)
            assertTrue(f.home.state.value.isRefreshingSubscriptions)
            gate.complete(Unit)
            runCurrent()
            assertFalse(f.home.state.value.isRefreshingSubscriptions)
            assertTrue(f.home.state.value.subscriptionRefreshFailed)
            assertEquals(1, f.repository.getAllLocations().size)
        }
    }

    @Test fun stoppedConnectionIsNotStartedByAnUpdateFinishingLater() = runTest {
        withHome { f ->
            val gate = CompletableDeferred<Unit>()
            f.refresh = {
                gate.await()
                f.repository.saveBundle(LocationBundleV4(activeLocationId = "active", locations = listOf(profile("active", "b"))))
                1
            }
            f.home.refreshSubscriptions()
            runCurrent()
            f.vpn.stopVpn()
            gate.complete(Unit)
            runCurrent()
            assertEquals(0, f.vpn.startCalls)
            assertEquals("b".repeat(64), f.home.state.value.configData.key)
        }
    }

    @Test fun scheduledRefreshAlsoAppliesChangedActiveConfig() = runTest {
        withHome { f ->
            f.dueRefresh = {
                f.repository.saveBundle(LocationBundleV4(activeLocationId = "active", locations = listOf(profile("active", "c"))))
                1
            }
            f.home.onForeground()
            f.home.state.first { it.configData.key == "c".repeat(64) }
            runCurrent()
            assertEquals("c".repeat(64), f.home.state.value.configData.key)
            assertEquals(1, f.vpn.startCalls)
        }
    }

    @Test fun vpnConflictReasonIsVisibleAndClearedOnTheNextAttempt() = runTest {
        withHome { f ->
            f.vpn.status.value = VpnStatus.Error(ConnectionPreflight.VPN_ACTIVE)
            runCurrent()
            assertEquals(ConnectionPreflight.VPN_ACTIVE, f.home.state.value.connectionError)
            f.vpn.status.value = VpnStatus.Connecting
            runCurrent()
            assertNull(f.home.state.value.connectionError)
        }
    }

    private suspend fun TestScope.withHome(test: suspend (Fixture) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = MemorySource(LocationBundleV4(activeLocationId = "active", locations = listOf(profile("active"))))
        val http = HttpClient(MockEngine { error("Unexpected network request") })
        val base = LocationsRepositoryImpl(source, httpClient = http)
        val fixture = Fixture()
        fixture.repository = object : LocationsRepository by base {
            override suspend fun refreshSubscriptions(subscriptionProxy: SubscriptionFetchProxy?): Int {
                fixture.refreshCalls++
                return fixture.refresh()
            }
            override suspend fun refreshDueSubscriptions(subscriptionProxy: SubscriptionFetchProxy?): Int {
                val next = fixture.dueRefresh ?: return 0
                fixture.dueRefresh = null
                return next()
            }
            override suspend fun nextSubscriptionRefreshAtEpochMs(): Long? = null
        }
        fixture.home = HomeScreenViewModel(fixture.vpn, fixture.repository, NoImporter, NoExporter,
            subscriptionRefreshDispatcher = dispatcher)
        try {
            runCurrent()
            test(fixture)
        } finally {
            fixture.home.viewModelScope.cancel()
            http.close()
            Dispatchers.resetMain()
        }
    }

    private class Fixture {
        lateinit var repository: LocationsRepository
        lateinit var home: HomeScreenViewModel
        val vpn = FakeVpn()
        var refresh: suspend () -> Int = { 1 }
        var dueRefresh: (suspend () -> Int)? = null
        var refreshCalls = 0
    }

    private fun profile(id: String, key: String = "a") = LocationEntry.from(id,
        LocationConfig(id, "room-$id", key.repeat(64)), subscriptionUrl = "https://subscription.example/test")

    private class MemorySource(var stored: LocationBundleV4) : LocationsDataSource {
        override suspend fun loadLocationBundle() = stored
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private class FakeVpn : VpnManager {
        override val status = MutableStateFlow<VpnStatus>(VpnStatus.Connected)
        override val logs = MutableStateFlow<List<String>>(emptyList())
        override val isConnected = MutableStateFlow(true)
        var startCalls = 0
        var stopCalls = 0
        override fun needsPermission() = false
        override fun startVpn() { startCalls++ }
        override fun stopVpn() { stopCalls++; status.value = VpnStatus.Disconnected; isConnected.value = false }
        override suspend fun ping(locationConfig: LocationConfig, profile: VpnProfileConfig): Long? = null
        override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
    }

    private object NoImporter : ConfigImporter {
        override fun getFromClipboard(): String? = error("Unexpected clipboard read")
        override fun copyToClipboard(text: String) = error("Unexpected clipboard write")
        override suspend fun readTextFromSource(source: Any): String? = error("Unexpected import")
    }

    private object NoExporter : LogExporter {
        override suspend fun writeLogs(target: Any, content: String): Result<String> = error("Unexpected export")
    }
}
