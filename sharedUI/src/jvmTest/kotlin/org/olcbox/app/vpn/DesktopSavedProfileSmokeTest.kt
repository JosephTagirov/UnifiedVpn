package org.olcbox.app.vpn

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import java.nio.file.Files
import java.nio.file.Path
import java.net.Socket
import java.net.Proxy
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopSavedProfileSmokeTest {
    @Test fun savedProfileSupportsUnauthenticatedBrowserSocksAndStops() = runBlocking {
        val fixture = System.getenv("UNIFIEDVPN_SAVED_PROFILE_FIXTURE") ?: return@runBlocking
        require(System.getProperty("os.name").contains("Windows", true))
        val root = Path.of(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT"))
        require(Path.of(System.getenv("APPDATA")).startsWith(root))
        val data = Files.createDirectories(root.resolve("saved-profile"))
        Files.copy(Path.of(fixture), data.resolve("locations_v4.json"))
        val repo = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(data))
        assertTrue(repo.getAllLocations().isNotEmpty())
        val manager = DesktopVpnManager(repo)
        val systemProxy = System.getenv("UNIFIEDVPN_TEST_SYSTEM_PROXY") == "1"
        val mode = if (systemProxy) DesktopRoutingMode.SystemProxy else DesktopRoutingMode.LocalSocks
        manager.updateSocksProxySettings(DesktopSocksProxySettings(port = 19873,
            routingMode = mode, externalRoutingMode = mode))
        try {
            manager.startVpn()
            val status = withTimeout(160_000) { manager.status.first { it is VpnStatus.Connected || it is VpnStatus.Error } }
            assertTrue(status is VpnStatus.Connected, manager.logs.value.joinToString("\n"))
            val proxy = assertNotNull(manager.subscriptionFetchProxy())
            assertTrue(proxy.username.isBlank())
            for (url in listOf("https://www.wikipedia.org", "https://www.instagram.com")) {
                val proc = ProcessBuilder("C:/Windows/System32/curl.exe", "--silent", "--show-error", "--max-time", "45",
                    "--noproxy", "", "--socks5-hostname", "127.0.0.1:${proxy.port}", "--output", "NUL",
                    "--write-out", "%{http_code}", url).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                assertEquals(0, proc.waitFor(), output + "\n" + manager.logs.value.joinToString("\n"))
                assertTrue(output.trim().toIntOrNull() in 200..499, output)
                println("Verified $url through saved profile: HTTP ${output.trim()}")
            }
            if (systemProxy) {
                val chrome = Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe")
                require(Files.isExecutable(chrome))
                for ((index, host) in listOf("www.wikipedia.org", "www.instagram.com", "www.psbank.ru").withIndex()) {
                    val browserData = Files.createDirectories(root.resolve("chrome-system-proxy-$index"))
                    val outputFile = root.resolve("chrome-system-proxy-$index.private.html")
                    // Chrome receives its proxy from Windows. No proxy, DNS,
                    // certificate, QUIC, or browser-security override is used.
                    val proc = ProcessBuilder(chrome.toString(), "--headless", "--disable-gpu", "--no-first-run",
                        "--user-data-dir=$browserData", "--dump-dom", "https://$host")
                        .redirectOutput(outputFile.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    try {
                        assertTrue(proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "Chrome did not complete $host")
                        assertEquals(0, proc.exitValue(), "Chrome failed $host")
                        val html = Files.readString(outputFile)
                        assertTrue(html.length > 200 && !html.contains("chrome-error://") &&
                            !html.contains("ERR_PROXY_CONNECTION_FAILED") && !html.contains("ERR_CONNECTION_RESET"),
                            "Chrome returned a network error for $host")
                        println("Verified ordinary Windows proxy settings in Chrome: $host")
                    } finally {
                        if (proc.isAlive) proc.destroyForcibly().waitFor()
                    }
                }
            }
            val supportPorts = manager.logs.value.mapNotNull {
                Regex("VK Calls support SOCKS: 127\\.0\\.0\\.1:([0-9]+)").find(it)?.groupValues?.get(1)?.toInt()
            }
            if (System.getenv("UNIFIEDVPN_EXPECT_VK_SUPPORT") == "1") assertTrue(supportPorts.isNotEmpty(),"Support channel was not started")
            manager.stopVpn()
            assertTrue(withTimeout(15_000) { manager.status.first { it is VpnStatus.Disconnected || it is VpnStatus.Error } } is VpnStatus.Disconnected)
            for (supportPort in supportPorts) withTimeout(5_000) {
                while (Socket(Proxy.NO_PROXY).use { socket ->
                    runCatching { socket.connect(InetSocketAddress("127.0.0.1",supportPort),300) }.isSuccess
                }) kotlinx.coroutines.delay(100)
            }
            withTimeout(5_000) {
                while (Socket(Proxy.NO_PROXY).use { socket ->
                    runCatching { socket.connect(InetSocketAddress("127.0.0.1",proxy.port),1000) }.isSuccess
                }) kotlinx.coroutines.delay(100)
            }
        } finally { manager.close() }
    }
}
