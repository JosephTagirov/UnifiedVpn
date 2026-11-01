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
import java.net.ServerSocket
import java.util.UUID

internal object AndroidProfileLatency {
    suspend fun ping(context: Context, location: LocationConfig, profile: VpnProfileConfig): Long? = ProfileLatency.isolated {
        withContext(Dispatchers.IO) {
            val port=ServerSocket(0).use { it.localPort }
            val user=UUID.randomUUID().toString().replace("-","")
            val password=UUID.randomUUID().toString().replace("-","")
            // Only a private loopback SOCKS process; no VpnService or new Android TUN.
            val engine=createSocksBackedVpnEngine(context,profile,port,user,password,{ android.util.Log.d("UnifiedVPN.Latency",org.olcbox.app.data.logging.sanitizeDiagnosticLogLine(it)) },location=location)
            try {
                withTimeoutOrNull(95_000) {
                    engine.start()
                    ProfileLatency.measure(SubscriptionFetchProxy(engine.socksHost,engine.socksPort,
                        if(engine.usesAppSocksCredentials) user else "",if(engine.usesAppSocksCredentials) password else ""),onFailure={ error ->
                            android.util.Log.w("UnifiedVPN.Latency",org.olcbox.app.data.logging.sanitizeDiagnosticLogLine(error.toString()))
                        })
                }
            } catch(cancel: CancellationException) { throw cancel }
              catch(_: Exception) { null }
              finally { withContext(NonCancellable + Dispatchers.IO) { engine.stop() } }
        }
    }
}
