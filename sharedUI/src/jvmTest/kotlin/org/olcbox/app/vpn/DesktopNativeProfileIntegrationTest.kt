package org.olcbox.app.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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

    @Test
    fun realWindowsOpenFluxProfileProxiesHttpsAndStops() = runBlocking {
        if (System.getenv(OPENFLUX_TEST_GATE_ENV) != "1") return@runBlocking
        require(System.getProperty("os.name").contains("windows", ignoreCase = true))
        val isolatedRoot = requireIsolatedDesktopTestAppData()
        val protectedRoot = privateProfilePath(OPENFLUX_TEST_ROOT_ENV)
            ?.toAbsolutePath()?.normalize()
            ?: error("OpenFlux integration requires a protected test root")
        require(isolatedRoot == protectedRoot) { "OpenFlux test data must remain in its protected root" }
        require(System.getenv("LOCALAPPDATA")?.let(Path::of)?.toAbsolutePath()?.normalize()
            == isolatedRoot.resolve("Local")) { "OpenFlux integration requires isolated local application data" }
        require(System.getenv("APPDATA")?.let(Path::of)?.toAbsolutePath()?.normalize()
            == isolatedRoot.resolve("Roaming")) { "OpenFlux integration requires isolated roaming application data" }
        val profilePath = privateProfilePath(OPENFLUX_PROFILE_ENV)
            ?.toAbsolutePath()?.normalize()
            ?: error("OpenFlux integration requires an explicit private profile")
        require(profilePath.startsWith(protectedRoot)) { "OpenFlux profile must be inside the protected test root" }

        runProfile(
            rawConfig = Files.readString(profilePath).trim(),
            expectedType = VpnProfileConfig.TYPE_OPENFLUX,
            protectedDataRoot = protectedRoot
        )
    }

    private suspend fun runProfile(rawConfig: String, expectedType: String, protectedDataRoot: Path? = null) {
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) return
        requireIsolatedDesktopTestAppData()
        val isOpenFlux = expectedType == VpnProfileConfig.TYPE_OPENFLUX
        val dataDir = if (isOpenFlux) {
            Files.createTempDirectory(requireNotNull(protectedDataRoot), "desktop-profile-test-")
        } else {
            Files.createTempDirectory("unified-vpn-native-profile-test-")
        }
        val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(dataDir))
        val manager = DesktopVpnManager(repository)

        try {
            assertTrue(repository.importText(rawConfig), "Private profile import failed")
            val imported = assertNotNull(repository.getActiveLocation())
            assertEquals(expectedType, imported.profile.normalizedType)
            manager.updateSocksProxySettings(
                DesktopSocksProxySettings(
                    port = if (isOpenFlux) unusedLoopbackPort() else 11920,
                    username = "integration-user",
                    password = "integration-password",
                    routingMode = DesktopRoutingMode.LocalSocks,
                    externalRoutingMode = DesktopRoutingMode.LocalSocks
                )
            )

            manager.startVpn()
            withTimeout(START_TRANSITION_TIMEOUT_MS) {
                manager.status.first { status ->
                    status is VpnStatus.Connecting || status is VpnStatus.Reconnecting ||
                        status is VpnStatus.Connected || status is VpnStatus.Error
                }
            }
            val terminalStatus = withTimeout(if (isOpenFlux) OPENFLUX_CONNECTION_TIMEOUT_MS else CONNECTION_TIMEOUT_MS) {
                manager.status.first { status ->
                    status is VpnStatus.Connected || status is VpnStatus.Error
                }
            }
            assertTrue(
                terminalStatus is VpnStatus.Connected,
                manager.logs.value.joinToString(separator = "\n")
            )

            val proxy = assertNotNull(manager.subscriptionFetchProxy())
            val ownedOpenFlux = if (isOpenFlux) {
                assertEquals("127.0.0.1", proxy.host)
                assertTrue(proxy.port !in PROTECTED_HOST_PORTS, "OpenFlux test selected a reserved host port")
                assertTrue(manager.logs.value.contains("OpenFlux: OPENFLUX_READY"), "Authenticated Ready was not observed")
                assertNotNull(ownedOpenFluxProcess(protectedDataRoot!!), "Owned OpenFlux child process is missing")
            } else null
            val targetHosts = if (isOpenFlux) listOf("www.instagram.com", "telegram.org")
                else listOf("www.instagram.com", "www.wikipedia.org")
            for (host in targetHosts) {
                val statusCode = assertSocks5Https(proxy, host)
                if (isOpenFlux) {
                    assertTrue(ownedOpenFlux!!.isAlive, "OpenFlux exited during HTTPS traffic")
                    assertTrue(manager.status.value is VpnStatus.Connected, "OpenFlux lost its connected state")
                    println("OpenFlux HTTPS $host status=$statusCode")
                }
            }
            if (isOpenFlux) {
                repeat(5) {
                    delay(5_000L)
                    assertTrue(ownedOpenFlux!!.isAlive, "OpenFlux exited during the liveness window")
                    assertTrue(manager.status.value is VpnStatus.Connected, "OpenFlux lost its connected state")
                }
                val liveStatus = assertSocks5Https(proxy, "telegram.org")
                assertTrue(ownedOpenFlux!!.isAlive, "OpenFlux exited during the final HTTPS liveness check")
                assertTrue(manager.status.value is VpnStatus.Connected, "OpenFlux lost its connected state")
                println("OpenFlux authenticated Ready and 25-second HTTPS liveness verified; telegram.org status=$liveStatus")
            }

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
            if (isOpenFlux) {
                withTimeout(STOP_TIMEOUT_MS) {
                    while (ownedOpenFlux!!.isAlive) delay(100L)
                }
                val listenerClosed = Socket().use { socket ->
                    runCatching { socket.connect(InetSocketAddress(proxy.host, proxy.port), 1_000) }
                        .exceptionOrNull() is ConnectException
                }
                assertTrue(listenerClosed, "OpenFlux SOCKS listener remains open after stop")
                val runtimeDir = protectedDataRoot!!.resolve("Roaming/Olcbox/runtime")
                if (Files.exists(runtimeDir)) {
                    Files.list(runtimeDir).use { files ->
                        assertTrue(files.noneMatch { it.fileName.toString().startsWith("openflux-") }, "OpenFlux runtime config remains after stop")
                    }
                }
                println("OpenFlux owned process stopped, SOCKS listener closed, runtime config removed")
            }
        } finally {
            manager.close()
            deleteRecursively(dataDir)
        }
    }

    private fun requireIsolatedDesktopTestAppData(): Path {
        val expectedRoot = System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: error("Desktop integration tests require isolated application data")
        val appData = System.getenv("APPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: error("APPDATA is unavailable")
        require(appData.startsWith(expectedRoot)) {
            "Desktop integration tests refuse to use normal application data"
        }
        return expectedRoot
    }

    private fun unusedLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort.also { port -> require(port !in PROTECTED_HOST_PORTS) }
    }

    private fun ownedOpenFluxProcess(protectedRoot: Path): ProcessHandle? {
        val expectedBinary = protectedRoot.resolve("Roaming/Olcbox/bin/openflux-windows-amd64.exe")
        return ProcessHandle.current().children().use { children ->
            children.iterator().asSequence().singleOrNull { child ->
                child.isAlive && child.info().command().orElse(null)
                    ?.let(Path::of)?.toAbsolutePath()?.normalize() == expectedBinary
            }
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
    ): Int {
        var lastFailure: Throwable? = null
        repeat(HTTPS_ATTEMPTS) { attempt ->
            try {
                return assertSocks5HttpsOnce(proxy, targetHost)
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
    ): Int {
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
                return statusLine!!.substringAfter(' ').take(3).toInt()
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
        const val OPENFLUX_PROFILE_ENV = "UNIFIEDVPN_PRIVATE_OPENFLUX_PROFILE"
        const val OPENFLUX_TEST_GATE_ENV = "UNIFIEDVPN_RUN_OPENFLUX_INTEGRATION"
        const val OPENFLUX_TEST_ROOT_ENV = "UNIFIEDVPN_OPENFLUX_SMOKE_ROOT"
        val PROTECTED_HOST_PORTS = setOf(10808, 18080)
        const val START_TRANSITION_TIMEOUT_MS = 10_000L
        const val CONNECTION_TIMEOUT_MS = 35_000L
        const val OPENFLUX_CONNECTION_TIMEOUT_MS = 100_000L
        const val STOP_TIMEOUT_MS = 15_000L
        const val HTTPS_ATTEMPTS = 3
        const val HTTPS_RETRY_DELAY_MS = 2_000L
    }
}
