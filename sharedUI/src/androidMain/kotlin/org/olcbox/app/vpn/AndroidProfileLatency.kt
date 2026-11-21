package org.olcbox.app.vpn

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.service.createSocksBackedVpnEngine
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.EntryFailoverHealthProbe
import java.net.ServerSocket
import java.util.UUID

internal object AndroidProfileLatency {
    suspend fun ping(context: Context, location: LocationConfig, profile: VpnProfileConfig): Long? =
        withIsolatedProxy(context, location, profile, 95_000L) { proxy, usesEntryFailover ->
            ProfileLatency.measure(
                proxy,
                onFailure = { error ->
                    android.util.Log.w(
                        "UnifiedVPN.Latency",
                        org.olcbox.app.data.logging.sanitizeDiagnosticLogLine(error.toString())
                    )
                },
                probe = { candidate ->
                    if (usesEntryFailover) {
                        EntryFailoverHealthProbe.healthy(
                            XraySocksSettings(candidate.host, candidate.port, candidate.username, candidate.password)
                        )
                    } else {
                        OpenFluxTunnelPing.probeHttps(candidate)
                    }
                }
            )
        }

    suspend fun throughput(context: Context, location: LocationConfig, profile: VpnProfileConfig): Long? =
        withIsolatedProxy(context, location, profile, 35_000L) { proxy, _ -> ProfileThroughput.measure(proxy) }

    private suspend fun <T> withIsolatedProxy(
        context: Context,
        location: LocationConfig,
        profile: VpnProfileConfig,
        timeoutMs: Long,
        probe: suspend (SubscriptionFetchProxy, Boolean) -> T?
    ): T? = ProfileLatency.isolated {
        withContext(Dispatchers.IO) {
            val port=ServerSocket(0).use { it.localPort }
            val user=UUID.randomUUID().toString().replace("-","")
            val password=UUID.randomUUID().toString().replace("-","")
            // Only a private loopback SOCKS process; no VpnService or new Android TUN.
            val engine=createSocksBackedVpnEngine(context,profile,port,user,password,{ android.util.Log.d("UnifiedVPN.Latency",org.olcbox.app.data.logging.sanitizeDiagnosticLogLine(it)) },location=location,
                entryFailoverEnabled=OlcboxVpnState.connectedLatencySession()==null,directRoutingEnabled=false)
            try {
                withTimeoutOrNull(timeoutMs) {
                    engine.start()
                    val proxy = SubscriptionFetchProxy(engine.socksHost,engine.socksPort,
                        if(engine.usesAppSocksCredentials) user else "",if(engine.usesAppSocksCredentials) password else "")
                    probe(proxy, engine.usesEntryFailover)
                }
            } catch(cancel: CancellationException) { throw cancel }
              catch(unavailable: VpnPingUnavailableException) { throw unavailable }
              catch(_: Exception) { null }
              finally { withContext(NonCancellable + Dispatchers.IO) { engine.stop() } }
        }
    }
}
