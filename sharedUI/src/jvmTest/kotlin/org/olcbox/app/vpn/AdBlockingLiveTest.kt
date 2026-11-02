package org.olcbox.app.vpn

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Explicit opt-in. Uses a private fixture, only loopback SOCKS, and no OS proxy/TUN changes. */
class AdBlockingLiveTest {
    @Test fun ordinaryHttpsWorksThroughTheFilteredTunnel(): Unit = runBlocking {
        val fixture = System.getenv("UNIFIEDVPN_ADBLOCK_VLESS_FIXTURE") ?: return@runBlocking
        val root = Path.of(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT"))
        require(Path.of(System.getenv("APPDATA")).startsWith(root))
        val data = Files.createTempDirectory(root,"adblock-profile-")
        val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(data))
        repository.importText(Files.readString(Path.of(fixture)))
        repository.setActiveLocationId(repository.getAllLocations().first().storageId)
        val enabled = System.getenv("UNIFIEDVPN_ADBLOCK_DISABLE") != "1"
        val manager = DesktopVpnManager.latencyProbe(repository)
        manager.updateSocksProxySettings(DesktopSocksProxySettings(
            port=ServerSocket(0).use { it.localPort }, blockAds=enabled,
            routingMode=DesktopRoutingMode.LocalSocks,externalRoutingMode=DesktopRoutingMode.LocalSocks))
        try {
            manager.startVpn()
            val state = withTimeout(120_000) { manager.status.first { it is VpnStatus.Connected || it is VpnStatus.Error } }
            if (state is VpnStatus.Error) {
                Files.writeString(root.resolve("adblocking-startup.private.log"), manager.logs.value.joinToString("\n"))
            }
            assertTrue(state is VpnStatus.Connected,"Private VLESS fixture did not connect; see isolated private startup log")
            if (enabled) assertTrue(manager.logs.value.any { it.contains("Ad blocking enabled") })
            val proxy=assertNotNull(manager.subscriptionFetchProxy())
            for (url in listOf("https://www.wikipedia.org","https://www.instagram.com")) {
                val result=curl(proxy.port,url)
                assertEquals(0,result.first,"HTTPS request failed")
                assertEquals("200",result.second.trim(),"HTTPS endpoint did not return 200")
            }
            if (enabled) assertNotEquals(0,curl(proxy.port,"https://googleads.g.doubleclick.net").first,"Ad domain was not blocked")
            manager.stopVpn()
            assertTrue(withTimeout(15_000){manager.status.first{it is VpnStatus.Disconnected||it is VpnStatus.Error}} is VpnStatus.Disconnected)
        } finally { manager.close() }
    }

    private fun curl(port: Int,url: String): Pair<Int,String> {
        val process=ProcessBuilder("C:/Windows/System32/curl.exe","--silent","--max-time","35",
            "--noproxy","","--socks5-hostname","127.0.0.1:$port","--output","NUL","--write-out","%{http_code}",url)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if(!process.waitFor(40,TimeUnit.SECONDS)){process.destroyForcibly();return -1 to ""}
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
}
