package org.olcbox.app.vpn

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopNativeProfileIntegrationTest {
    @Test
    fun realWindowsVlessXhttpProfileProxiesHttpsAndStops() = runBlocking {
        val profilePath = privateProfilePath(VLESS_PROFILE_ENV) ?: return@runBlocking
        runProfile(
            rawConfig = Files.readString(profilePath).trim(),
            expectedType = VpnProfileConfig.TYPE_VLESS
        )
    }

    @Test
    fun realWindowsAmneziaProfileProxiesHttpsAndStops() = runBlocking {
        val profilePath = privateProfilePath(AWG_PROFILE_ENV) ?: return@runBlocking
        runProfile(
            rawConfig = Files.readString(profilePath).trim(),
            expectedType = VpnProfileConfig.TYPE_AMNEZIA_VPN
        )
    }

    private suspend fun runProfile(rawConfig: String, expectedType: String) {
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) return
        val dataDir = Files.createTempDirectory("unified-vpn-native-profile-test-")
        val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(dataDir))
        val manager = DesktopVpnManager(repository)

        try {
            assertTrue(repository.importText(rawConfig), "Private profile import failed")
            val imported = assertNotNull(repository.getActiveLocation())
            assertEquals(expectedType, imported.profile.normalizedType)
            manager.updateSocksProxySettings(
                DesktopSocksProxySettings(
                    port = 11920,
                    username = "integration-user",
                    password = "integration-password",
                    routingMode = DesktopRoutingMode.LocalSocks
                )
            )

            manager.startVpn()
            withTimeout(START_TRANSITION_TIMEOUT_MS) {
                manager.status.first { status ->
                    status is VpnStatus.Connecting || status is VpnStatus.Reconnecting
                }
            }
            val terminalStatus = withTimeout(CONNECTION_TIMEOUT_MS) {
                manager.status.first { status ->
                    status is VpnStatus.Connected || status is VpnStatus.Error
                }
            }
            assertTrue(
                terminalStatus is VpnStatus.Connected,
                manager.logs.value.joinToString(separator = "\n")
            )

            val proxy = assertNotNull(manager.subscriptionFetchProxy())
            assertSocks5Https(proxy, "www.instagram.com")
            assertSocks5Https(proxy, "www.wikipedia.org")

            manager.stopVpn()
            val stoppedStatus = withTimeout(STOP_TIMEOUT_MS) {
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
            deleteRecursively(dataDir)
        }
    }

    private fun privateProfilePath(variable: String): Path? {
        return System.getenv(variable)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(Path::of)
    }

    private fun assertSocks5Https(
        proxy: org.olcbox.app.data.repository.SubscriptionFetchProxy,
        targetHost: String
    ) {
        var lastFailure: Throwable? = null
        repeat(HTTPS_ATTEMPTS) { attempt ->
            try {
                assertSocks5HttpsOnce(proxy, targetHost)
                return
            } catch (failure: Exception) {
                lastFailure = failure
            } catch (failure: AssertionError) {
                lastFailure = failure
            }

            if (attempt < HTTPS_ATTEMPTS - 1) {
                Thread.sleep(HTTPS_RETRY_DELAY_MS)
            }
        }
        throw AssertionError(
            "No valid HTTPS response from $targetHost through Unified VPN after $HTTPS_ATTEMPTS attempts",
            lastFailure
        )
    }

    private fun assertSocks5HttpsOnce(
        proxy: org.olcbox.app.data.repository.SubscriptionFetchProxy,
        targetHost: String
    ) {
        val username = proxy.username.toByteArray(StandardCharsets.UTF_8)
        val password = proxy.password.toByteArray(StandardCharsets.UTF_8)
        val target = targetHost.toByteArray(StandardCharsets.US_ASCII)
        require(target.size <= 255)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.host, proxy.port), 10_000)
            socket.soTimeout = 30_000
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
            output.write(byteArrayOf(0x01, 0xBB.toByte()))
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

            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, targetHost, 443, true) as SSLSocket
            tls.use {
                it.soTimeout = 30_000
                it.sslParameters = it.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                it.startHandshake()
                it.outputStream.write(
                    (
                        "GET / HTTP/1.1\r\n" +
                            "Host: $targetHost\r\n" +
                            "User-Agent: UnifiedVPN-connection-test/0.0.10\r\n" +
                            "Accept: */*\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
                )
                it.outputStream.flush()
                val statusLine = it.inputStream.bufferedReader(StandardCharsets.US_ASCII).readLine()
                assertTrue(
                    statusLine?.matches(Regex("HTTP/\\d(?:\\.\\d)? [1-5]\\d{2}.*")) == true,
                    "No valid HTTPS response from $targetHost through Unified VPN: $statusLine"
                )
            }
        }
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        require(size >= 0)
        return ByteArray(size).also { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                val read = read(bytes, offset, bytes.size - offset)
                check(read >= 0) { "Unexpected end of SOCKS5 response" }
                offset += read
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val VLESS_PROFILE_ENV = "UNIFIEDVPN_PRIVATE_VLESS_PROFILE"
        const val AWG_PROFILE_ENV = "UNIFIEDVPN_PRIVATE_AWG_PROFILE"
        const val START_TRANSITION_TIMEOUT_MS = 10_000L
        const val CONNECTION_TIMEOUT_MS = 35_000L
        const val STOP_TIMEOUT_MS = 15_000L
        const val HTTPS_ATTEMPTS = 3
        const val HTTPS_RETRY_DELAY_MS = 2_000L
    }
}
