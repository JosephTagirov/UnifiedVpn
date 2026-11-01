package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.LocationsRepository
import java.net.ServerSocket

internal object DesktopProfileLatency {
    suspend fun ping(repository: LocationsRepository, location: LocationConfig, profile: VpnProfileConfig): Long? = ProfileLatency.isolated {
        withContext(Dispatchers.IO) {
            val entries = repository.getAllLocations()
            val entry = entries.firstOrNull { ProfileLatencySession(it.profile,it.location,org.olcbox.app.data.repository.SubscriptionFetchProxy("127.0.0.1",1)).matches(profile,location) }
                ?: if(profile.isOlcRtc()) LocationEntry.from("latency-probe",location) else LocationEntry.fromProfile("latency-probe",profile)
            val pinned = object : LocationsRepository by repository {
                override suspend fun getActiveLocation(): LocationEntry = entry
            }
            // The probe constructor never recovers or changes Windows proxy settings.
            val manager = DesktopVpnManager.latencyProbe(pinned)
            manager.updateSocksProxySettings(DesktopSocksProxySettings(host="127.0.0.1",port=ServerSocket(0).use { it.localPort },
                username="",password="",routingMode=DesktopRoutingMode.LocalSocks,externalRoutingMode=DesktopRoutingMode.LocalSocks))
            try {
                withTimeoutOrNull(95_000) {
                    manager.startVpn()
                    val state=manager.status.first { it is VpnStatus.Connected || it is VpnStatus.Error }
                    if(state !is VpnStatus.Connected) return@withTimeoutOrNull null
                    val proxy=manager.subscriptionFetchProxy() ?: return@withTimeoutOrNull null
                    ProfileLatency.measure(proxy)
                }
            } catch(cancel: CancellationException) { throw cancel }
              catch(_: Exception) { null }
              finally { withContext(NonCancellable + Dispatchers.IO) { manager.close() } }
        }
    }
}
