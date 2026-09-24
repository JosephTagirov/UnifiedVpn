package org.olcbox.app.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.io.DataInputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenFluxTunnelPingTest {
    @Test
    fun pingsOnlyMatchingConnectedConfigAndIgnoresDisplayNameAndSerialization() = runTest {
        val profile = profile()
        val tunnel = tunnel(profile)
        val renamedUri = profile.copy(name = "Renamed", rawConfig = null, uri = config().toUri())
        val result = OpenFluxTunnelPing.ping(renamedUri, { tunnel }) { proxy ->
            assertEquals(tunnel.proxy, proxy)
            true
        }
        assertTrue(assertNotNull(result) >= 1)
    }

    @Test
    fun inactiveOrWrongDocumentOrKeyNeverInvokesTheNetworkProbe() = runTest {
        val connected = tunnel(profile())
        val differentDocument = profile(config().copy(documentUrl = "https://docs.yandex.ru/docs/view?url=other"))
        val differentKey = profile(config().copy(encryptionKey = "cd".repeat(32)))
        for ((requested, active) in listOf(
            profile() to null,
            differentDocument to connected,
            differentKey to connected,
            VpnProfileConfig(type = VpnProfileConfig.TYPE_VLESS) to connected
        )) {
            assertFailsWith<VpnPingUnavailableException> {
                OpenFluxTunnelPing.ping(requested, { active }) {
                    error("Inactive or mismatched OpenFlux profile must not send any traffic")
                }
            }
        }
    }

    @Test
    fun reconnectingEvenTheSameProfileInvalidatesAResult() = runTest {
        var active: OpenFluxConnectedTunnel? = tunnel(profile())
        assertFailsWith<VpnPingUnavailableException> {
            OpenFluxTunnelPing.ping(profile(), { active }) {
                active = null
                active = tunnel(profile())
                true
            }
        }
    }

    @Test
    fun disconnectDuringFailedProbeIsUnavailableRatherThanOffline() = runTest {
        var active: OpenFluxConnectedTunnel? = tunnel(profile())
        assertFailsWith<VpnPingUnavailableException> {
            OpenFluxTunnelPing.ping(profile(), { active }) {
                active = null
                throw IllegalStateException("Synthetic disconnect")
            }
        }
    }

    @Test
    fun failedConnectedProbeIsOfflineAndDoesNotRetryDirectly() = runTest {
        val active = tunnel(profile())
        var attempts = 0
        assertNull(OpenFluxTunnelPing.ping(profile(), { active }) {
            attempts++
            throw IllegalStateException("Synthetic transport failure")
        })
        assertEquals(1, attempts)
        assertNull(OpenFluxTunnelPing.ping(profile(), { active }) { false })
    }

    @Test
    fun timeoutIsBoundedAndCallerCancellationPropagates() = runTest {
        val active = tunnel(profile())
        var released = false
        assertNull(OpenFluxTunnelPing.ping(profile(), { active }, timeoutMs = 100) {
            try {
                delay(1_000)
                true
            } finally {
                released = true
            }
        })
        assertTrue(released)
        assertFailsWith<CancellationException> {
            OpenFluxTunnelPing.ping(profile(), { active }) {
                throw CancellationException("Synthetic caller cancellation")
            }
        }
    }

    @Test
    fun refusesExternalProxyAndIncompleteCredentialsAndNeverUsesGenericLocalhostPing() {
        assertNull(OpenFluxConnectedTunnel.from(profile(), proxy().copy(host = "example.invalid")))
        assertNull(OpenFluxConnectedTunnel.from(profile(), proxy().copy(password = "")))
        assertNull(OpenFluxConnectedTunnel.from(profile(), proxy().copy(port = 0)))
        assertNull(VpnProfileReachability.endpoint(profile().copy(localSocksPort = 12345)))
        val diagnostic = tunnel(profile()).toString()
        assertFalse(diagnostic.contains(config().encryptionKey))
        assertFalse(diagnostic.contains(config().documentUrl))
        assertFalse(diagnostic.contains(proxy().password))
    }

    @Test
    fun sendsDomainAndCredentialsToExplicitSocksWithoutLocalDnsResolution() = runBlocking {
        SocksFixture().use { socks ->
            val previous = Authenticator.getDefault()
            assertTrue(withTimeout(5_000) {
                OpenFluxTunnelPing.probeUrl(proxy().copy(port = socks.port), TEST_PROBE_URL)
            })
            val request = socks.request.get(5, TimeUnit.SECONDS)
            assertEquals("openflux-probe.invalid", request.host)
            assertEquals(80, request.port)
            assertEquals(proxy().username, request.username)
            assertEquals(proxy().password, request.password)
            assertTrue(request.http.startsWith("GET /generate_204 HTTP/1.1"))
            assertSame(previous, Authenticator.getDefault())
        }
    }

    @Test
    fun refusesOrdinaryHttpResponsesAndDoesNotFollowRedirects() = runBlocking {
        for (status in listOf("200 OK", "302 Found", "503 Service Unavailable")) {
            SocksFixture(status).use { socks ->
                assertFalse(withTimeout(5_000) {
                    OpenFluxTunnelPing.probeUrl(proxy().copy(port = socks.port), TEST_PROBE_URL)
                })
                assertEquals("openflux-probe.invalid", socks.request.get(5, TimeUnit.SECONDS).host)
            }
        }
    }

    @Test
    fun cancellationClosesTheSocksRequestAndRestoresGlobalAuthentication() = runBlocking {
        SocksFixture(responseStatus = null).use { socks ->
            val previous = Authenticator.getDefault()
            val job = async(Dispatchers.Default) {
                OpenFluxTunnelPing.probeUrl(proxy().copy(port = socks.port), TEST_PROBE_URL)
            }
            try {
                withContext(Dispatchers.IO) { socks.request.get(5, TimeUnit.SECONDS) }
                job.cancelAndJoin()
                assertSame(previous, Authenticator.getDefault())
                assertEquals(-1, withContext(Dispatchers.IO) { socks.closedByClient.get(5, TimeUnit.SECONDS) })
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private fun tunnel(profile: VpnProfileConfig) = assertNotNull(OpenFluxConnectedTunnel.from(profile, proxy()))

    private fun profile(config: OpenFluxProfileConfig = config()) = VpnProfileConfig(
        type = VpnProfileConfig.TYPE_OPENFLUX,
        name = "Synthetic Docs",
        rawConfig = config.toJson()
    )

    private fun config() = OpenFluxProfileConfig(
        documentUrl = "https://docs.yandex.ru/docs/view?url=synthetic",
        encryptionKey = "ab".repeat(32)
    )

    private fun proxy() = SubscriptionFetchProxy("127.0.0.1", 12345, "synthetic-user", "synthetic-password")

    private data class CapturedRequest(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val http: String
    )

    private class SocksFixture(private val responseStatus: String? = "204 No Content") : AutoCloseable {
        private val listener = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0))
            soTimeout = 5_000
        }
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "openflux-ping-fixture").apply { isDaemon = true }
        }
        private var client: Socket? = null
        val port: Int = listener.localPort
        val request = CompletableFuture<CapturedRequest>()
        val closedByClient = CompletableFuture<Int>()

        init {
            executor.submit {
                try {
                    listener.accept().use { socket ->
                        client = socket
                        socket.soTimeout = 5_000
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        check(input.readUnsignedByte() == 5)
                        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        check(2.toByte() in methods)
                        output.write(byteArrayOf(5, 2))
                        output.flush()
                        check(input.readUnsignedByte() == 1)
                        val username = readSizedString(input)
                        val password = readSizedString(input)
                        output.write(byteArrayOf(1, 0))
                        output.flush()
                        check(input.readUnsignedByte() == 5)
                        check(input.readUnsignedByte() == 1)
                        check(input.readUnsignedByte() == 0)
                        check(input.readUnsignedByte() == 3) { "Target must be resolved by SOCKS, not local DNS" }
                        val host = readSizedString(input)
                        val port = input.readUnsignedShort()
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        output.flush()
                        val http = StringBuilder()
                        while (!http.endsWith("\r\n\r\n")) {
                            check(http.length < 8192)
                            http.append(input.readUnsignedByte().toChar())
                        }
                        request.complete(CapturedRequest(host, port, username, password, http.toString()))
                        if (responseStatus == null) {
                            closedByClient.complete(input.read())
                        } else {
                            output.write(
                                ("HTTP/1.1 $responseStatus\r\nContent-Length: 0\r\n" +
                                    "Location: http://redirect.invalid/generate_204\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            output.flush()
                        }
                    }
                } catch (failure: Exception) {
                    request.completeExceptionally(failure)
                    closedByClient.completeExceptionally(failure)
                }
            }
        }

        override fun close() {
            client?.close()
            listener.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        private fun readSizedString(input: DataInputStream): String =
            ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
    }

    private companion object {
        const val TEST_PROBE_URL = "http://openflux-probe.invalid/generate_204"
    }
}
