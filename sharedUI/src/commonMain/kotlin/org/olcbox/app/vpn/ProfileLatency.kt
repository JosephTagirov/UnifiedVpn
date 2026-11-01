package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.time.TimeSource

/** Identity belongs to one engine generation; a different profile can never borrow its result. */
internal class ProfileLatencySession(val profile: VpnProfileConfig, val location: LocationConfig, val proxy: SubscriptionFetchProxy) {
    fun matches(other: VpnProfileConfig, config: LocationConfig): Boolean {
        if (profile.normalizedType != other.normalizedType) return false
        if (profile.isOlcRtc()) return location.normalized().copy(name="") == config.normalized().copy(name="")
        if (profile.isOpenFlux()) {
            fun parsed(p: VpnProfileConfig) = OpenFluxProfileConfig.parse(p.rawConfig?.takeIf { it.isNotBlank() } ?: p.uri)
            return parsed(profile)?.let { it == parsed(other) } == true
        }
        fun key(p: VpnProfileConfig) = p.normalized().copy(name=null,
            uri=p.uri?.substringBefore('#'),rawConfig=p.rawConfig?.let { if(it.startsWith("vless://")) it.substringBefore('#') else it })
        return key(profile) == key(other)
    }
    override fun toString() = "ProfileLatencySession(secrets=[redacted])"
}

internal object ProfileLatency {
    private val gate = Mutex()
    suspend fun <T> isolated(block: suspend () -> T): T = gate.withLock { block() }

    suspend fun measure(proxy: SubscriptionFetchProxy, onFailure: (Exception) -> Unit = {}, probe: suspend (SubscriptionFetchProxy) -> Boolean = OpenFluxTunnelPing::probeHttps): Long? {
        return try {
            withTimeoutOrNull(12_000) {
                val started = TimeSource.Monotonic.markNow()
                if (probe(proxy)) started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) else null
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (error: Exception) { onFailure(error);null }
    }

    suspend fun connected(session: ProfileLatencySession, current: () -> ProfileLatencySession?, probe: suspend (SubscriptionFetchProxy) -> Boolean = OpenFluxTunnelPing::probeHttps): Long? {
        if (current() !== session) throw VpnPingUnavailableException()
        val result = measure(session.proxy,probe=probe)
        if (current() !== session) throw VpnPingUnavailableException()
        return result
    }
}
