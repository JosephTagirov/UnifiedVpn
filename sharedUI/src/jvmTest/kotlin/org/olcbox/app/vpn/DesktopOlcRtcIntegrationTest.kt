package org.olcbox.app.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopOlcRtcIntegrationTest {
    @Test
    fun realWindowsClientProxiesTcpThroughCompatiblePeer() = runBlocking {
        val dataDir = System.getenv(TEST_DATA_DIR_ENV)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
            ?: return@runBlocking
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) {
            return@runBlocking
        }

        val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(dataDir))
        val profileName = System.getenv(TEST_PROFILE_ENV)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("$TEST_PROFILE_ENV is required for the private olcRTC integration test")
        val entry = repository.getAllLocations().single { it.name == profileName }
        repository.setActiveLocationId(entry.storageId)

        val manager = DesktopVpnManager(repository)
        manager.updateSocksProxySettings(
            DesktopSocksProxySettings(
                port = 11880,
                username = "integration-user",
                password = "integration-password",
                routingMode = DesktopRoutingMode.LocalSocks
            )
        )

        try {
            val terminalStatus = connectWithRetries(manager)
            assertTrue(
                terminalStatus is VpnStatus.Connected,
                manager.logs.value.joinToString(separator = "\n")
            )

            val proxy = assertNotNull(manager.subscriptionFetchProxy())
            assertSocks5TcpConnect(proxy, targetAddress = byteArrayOf(1, 1, 1, 1), targetPort = 443)
            assertHttpsViaSocks(proxy, "www.instagram.com")
            assertHttpsViaSocks(proxy, "www.wikipedia.org")

            manager.stopVpn()
            val stoppedStatus = withTimeout(15_000L) {
                manager.status.first { status ->
                    status is VpnStatus.Disconnected || status is VpnStatus.Error
                }
            }
            assertTrue(
                stoppedStatus is VpnStatus.Disconnected,
                manager.logs.value.joinToString(separator = "\n")
            )
        } finally {
            manager.close()
        }
    }

    private suspend fun connectWithRetries(manager: DesktopVpnManager): VpnStatus {
        var terminalStatus: VpnStatus = VpnStatus.Error("Connection was not attempted")
        repeat(CONNECTION_ATTEMPTS) { attempt ->
            manager.startVpn()
            val transitionStatus = withTimeoutOrNull(START_TRANSITION_TIMEOUT_MS) {
                manager.status.first { status ->
                    status is VpnStatus.Connecting ||
                        status is VpnStatus.Reconnecting ||
                        status is VpnStatus.Connected ||
                        status is VpnStatus.Error
                }
            }
            terminalStatus = when (transitionStatus) {
                is VpnStatus.Connected,
                is VpnStatus.Error -> transitionStatus
                null -> VpnStatus.Error("Connection attempt ${attempt + 1} did not start in time")
                else -> withTimeoutOrNull(CONNECTION_ATTEMPT_TIMEOUT_MS) {
                    manager.status.first { status ->
                        status is VpnStatus.Connected || status is VpnStatus.Error
                    }
                } ?: VpnStatus.Error("Connection attempt ${attempt + 1} timed out")
            }
            if (terminalStatus is VpnStatus.Connected) {
                println("olcRTC connected on attempt ${attempt + 1} of $CONNECTION_ATTEMPTS")
                return terminalStatus
            }

            println("olcRTC attempt ${attempt + 1} of $CONNECTION_ATTEMPTS did not connect")
            manager.stopVpn()
            val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                manager.status.first { status -> status is VpnStatus.Disconnected }
            }
            if (stopped == null) {
                error(
                    buildString {
                        append("olcRTC did not stop after failed attempt ")
                        append(attempt + 1)
                        append('\n')
                        append(manager.logs.value.joinToString(separator = "\n"))
                    }
                )
            }
            if (attempt + 1 < CONNECTION_ATTEMPTS) {
                delay(RETRY_DELAY_MS)
            }
        }
        return terminalStatus
    }

    private fun assertSocks5TcpConnect(
        proxy: org.olcbox.app.data.repository.SubscriptionFetchProxy,
        targetAddress: ByteArray,
        targetPort: Int
    ) {
        require(targetAddress.size == 4)
        val username = proxy.username.toByteArray(StandardCharsets.UTF_8)
        val password = proxy.password.toByteArray(StandardCharsets.UTF_8)
        require(username.size in 1..255)
        require(password.size in 1..255)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.host, proxy.port), 10_000)
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            assertContentEquals(byteArrayOf(5, 2), input.readExactly(2))

            output.write(byteArrayOf(1, username.size.toByte()))
            output.write(username)
            output.write(password.size)
            output.write(password)
            output.flush()
            assertContentEquals(byteArrayOf(1, 0), input.readExactly(2))

            output.write(byteArrayOf(5, 1, 0, 1))
            output.write(targetAddress)
            output.write(targetPort ushr 8)
            output.write(targetPort and 0xff)
            output.flush()

            val response = input.readExactly(4)
            assertEquals(5, response[0].toInt() and 0xff)
            assertEquals(0, response[1].toInt() and 0xff)
            when (response[3].toInt() and 0xff) {
                1 -> input.readExactly(4)
                3 -> input.readExactly(input.read())
                4 -> input.readExactly(16)
                else -> error("Unsupported SOCKS5 response address type")
            }
            input.readExactly(2)
        }
    }

    private fun assertHttpsViaSocks(
        proxy: org.olcbox.app.data.repository.SubscriptionFetchProxy,
        host: String
    ) {
        val username = proxy.username.toByteArray(StandardCharsets.UTF_8)
        val password = proxy.password.toByteArray(StandardCharsets.UTF_8)
        val target = host.toByteArray(StandardCharsets.US_ASCII)
        require(username.size in 1..255)
        require(password.size in 1..255)
        require(target.size in 1..255)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.host, proxy.port), 10_000)
            socket.soTimeout = 20_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            assertContentEquals(byteArrayOf(5, 2), input.readExactly(2))

            output.write(byteArrayOf(1, username.size.toByte()))
            output.write(username)
            output.write(password.size)
            output.write(password)
            output.flush()
            assertContentEquals(byteArrayOf(1, 0), input.readExactly(2))

            output.write(byteArrayOf(5, 1, 0, 3, target.size.toByte()))
            output.write(target)
            output.write(443 ushr 8)
            output.write(443 and 0xff)
            output.flush()

            val response = input.readExactly(4)
            assertEquals(5, response[0].toInt() and 0xff)
            assertEquals(0, response[1].toInt() and 0xff)
            when (response[3].toInt() and 0xff) {
                1 -> input.readExactly(4)
                3 -> input.readExactly(input.read())
                4 -> input.readExactly(16)
                else -> error("Unsupported SOCKS5 response address type")
            }
            input.readExactly(2)

            val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tlsSocket = socketFactory.createSocket(socket, host, 443, false) as SSLSocket
            tlsSocket.use { tls ->
                tls.soTimeout = 20_000
                tls.startHandshake()
                val request = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: ").append(host).append("\r\n")
                    append("User-Agent: UnifiedVPN-Integration-Test\r\n")
                    append("Accept: */*\r\n")
                    append("Connection: close\r\n\r\n")
                }
                tls.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
                tls.outputStream.flush()
                val statusLine = tls.inputStream.bufferedReader(StandardCharsets.US_ASCII).readLine().orEmpty()
                val statusCode = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})")
                    .find(statusLine)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                assertTrue(statusCode != null && statusCode in 100..599, "$host returned '$statusLine'")
                println("$host HTTPS status=$statusCode")
            }
        }
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        require(size >= 0)
        val result = ByteArray(size)
        var offset = 0
        while (offset < result.size) {
            val read = read(result, offset, result.size - offset)
            check(read >= 0) { "Unexpected end of SOCKS5 response" }
            offset += read
        }
        return result
    }

    private companion object {
        const val TEST_DATA_DIR_ENV = "UNIFIEDVPN_OLCRTC_TEST_DATA_DIR"
        const val TEST_PROFILE_ENV = "UNIFIEDVPN_OLCRTC_TEST_PROFILE"
        const val CONNECTION_ATTEMPTS = 6
        const val START_TRANSITION_TIMEOUT_MS = 10_000L
        const val CONNECTION_ATTEMPT_TIMEOUT_MS = 45_000L
        const val STOP_TIMEOUT_MS = 15_000L
        const val RETRY_DELAY_MS = 2_000L
    }
}
