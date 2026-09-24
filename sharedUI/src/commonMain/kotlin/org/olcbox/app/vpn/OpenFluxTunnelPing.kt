package org.olcbox.app.vpn

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.time.TimeSource

/** A new instance identifies each connected tunnel, even when reconnecting the same profile. */
internal class OpenFluxConnectedTunnel private constructor(
    private val config: OpenFluxProfileConfig,
    val proxy: SubscriptionFetchProxy
) {
    fun matches(profile: VpnProfileConfig): Boolean = config == profile.openFluxConfig()

    override fun toString(): String = "OpenFluxConnectedTunnel(secrets=[redacted])"

    companion object {
        fun from(profile: VpnProfileConfig, proxy: SubscriptionFetchProxy): OpenFluxConnectedTunnel? {
            val config = profile.openFluxConfig() ?: return null
            if (proxy.host !in setOf("127.0.0.1", "::1") || proxy.port !in 1..65535) return null
            if (proxy.username.isEmpty() != proxy.password.isEmpty()) return null
            return OpenFluxConnectedTunnel(config, proxy)
        }
    }
}

/** An unavailable measurement is not evidence that the remote server is offline. */
class VpnPingUnavailableException : Exception("Connect to this profile to check latency")

internal object OpenFluxTunnelPing {
    suspend fun ping(
        profile: VpnProfileConfig,
        connectedTunnel: () -> OpenFluxConnectedTunnel?,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        probe: suspend (SubscriptionFetchProxy) -> Boolean = ::probeHttps
    ): Long? {
        val tunnel = connectedTunnel()?.takeIf { it.matches(profile) }
            ?: throw VpnPingUnavailableException()
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                if (connectedTunnel() !== tunnel) throw VpnPingUnavailableException()
                val startedAt = TimeSource.Monotonic.markNow()
                val reachable = probe(tunnel.proxy)
                if (connectedTunnel() !== tunnel) throw VpnPingUnavailableException()
                if (reachable) startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) else null
            }
            if (connectedTunnel() !== tunnel) throw VpnPingUnavailableException()
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: VpnPingUnavailableException) {
            throw unavailable
        } catch (_: Exception) {
            if (connectedTunnel() !== tunnel) throw VpnPingUnavailableException()
            return null
        }
    }

    internal suspend fun probeHttps(proxy: SubscriptionFetchProxy): Boolean =
        probeUrl(proxy, PROBE_URL)

    internal suspend fun probeUrl(proxy: SubscriptionFetchProxy, url: String): Boolean {
        // Explicit SOCKS uses remote DNS; never retry this request without the tunnel.
        val baseClient = createProxyHttpClient(
            subscriptionProxy = proxy,
            connectTimeoutMs = REQUEST_TIMEOUT_MS,
            requestTimeoutMs = REQUEST_TIMEOUT_MS,
            socketTimeoutMs = REQUEST_TIMEOUT_MS,
            allowInsecureRequests = false
        )
        val client = baseClient.config { followRedirects = false }
        try {
            return withProxyAuthentication(proxy) {
                client.get(url).status == HttpStatusCode.NoContent
            }
        } finally {
            client.close()
            baseClient.close()
        }
    }

    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val PROBE_URL = "https://www.google.com/generate_204"
}

private fun VpnProfileConfig.openFluxConfig(): OpenFluxProfileConfig? {
    if (!isOpenFlux()) return null
    val profile = normalized()
    return OpenFluxProfileConfig.parse(profile.rawConfig?.takeIf { it.isNotBlank() } ?: profile.uri)
        ?.takeIf { it.isValid() }
}
