package org.olcbox.app.vpn

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.time.TimeSource

internal object ProfileThroughput {
    private const val PAYLOAD_BYTES = 1_048_576
    private const val TIMEOUT_MS = 15_000L
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=$PAYLOAD_BYTES"

    /** Returns bits per second after the headers, through the specified SOCKS path only. */
    suspend fun measure(proxy: SubscriptionFetchProxy): Long? {
        if (proxy.host !in setOf("127.0.0.1", "::1")) return null
        val baseClient = createProxyHttpClient(proxy, TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS)
        val client = baseClient.config { followRedirects = false }
        try {
            return withProxyAuthentication(proxy) {
                client.prepareGet(DOWNLOAD_URL) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                    header(HttpHeaders.CacheControl, "no-cache")
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) return@execute null
                    val input = response.bodyAsChannel()
                    val buffer = ByteArray(16_384)
                    val startedAt = TimeSource.Monotonic.markNow()
                    var remaining = PAYLOAD_BYTES
                    while (remaining > 0) {
                        val count = input.readAvailable(buffer, 0, minOf(remaining, buffer.size))
                        if (count < 0) return@execute null
                        remaining -= count
                    }
                    PAYLOAD_BYTES * 8_000_000_000L / startedAt.elapsedNow().inWholeNanoseconds.coerceAtLeast(1)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            client.close()
            baseClient.close()
        }
    }
}
