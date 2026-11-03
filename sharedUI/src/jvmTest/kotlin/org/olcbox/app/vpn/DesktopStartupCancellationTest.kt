package org.olcbox.app.vpn

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.vpn.desktop.DesktopProxyController
import java.nio.file.Files
import kotlin.test.*

class DesktopStartupCancellationTest {
    @Test fun stopCancelsAStalledPreflightWithoutWaitingForItsTimeout() = runBlocking {
        val directory=Files.createTempDirectory("unified-start-cancel-")
        val repository=LocationsRepositoryImpl(JvmLocationsDataSourceImpl(directory))
        repository.saveBundle(LocationBundleV4(activeLocationId="test",locations=listOf(
            LocationEntry.from("test",LocationConfig("Test","room","a".repeat(64))))))
        val entered=CompletableDeferred<Unit>()
        val cancelled=CompletableDeferred<Unit>()
        val proxy=object:DesktopProxyController {
            override suspend fun enable(pacUrl:String)=error("Cancelled startup must not enable proxy")
            override suspend fun restore()=Unit
        }
        val manager=DesktopVpnManager(repository,proxyController=proxy,checkActiveVpn={
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        try {
            manager.startVpn()
            withTimeout(2000) { entered.await() }
            manager.stopVpn()
            withTimeout(2000) {
                cancelled.await()
                manager.status.first { it is VpnStatus.Disconnected }
            }
            assertFalse(manager.isConnected.value)
        } finally { manager.close();directory.toFile().deleteRecursively() }
    }
}
