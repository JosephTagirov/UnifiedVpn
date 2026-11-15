package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.VpnEntryChoice

/** Explicit opt-in, isolated app data, real TLS/VLESS, and no OS proxy/TUN changes. */
class DesktopEntryFailoverLiveTest {
    @Test fun actualManagedVlessUsesAConfirmedEntryAndCleansItsPrivateNativeResources() = runBlocking {
        val fixture = System.getenv("UNIFIEDVPN_ENTRY_VLESS_FIXTURE") ?: return@runBlocking
        val isolated = Path.of(requireNotNull(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT"))).toAbsolutePath().normalize()
        for (variable in listOf("APPDATA", "LOCALAPPDATA"))
            require(Path.of(requireNotNull(System.getenv(variable))).toAbsolutePath().normalize().startsWith(isolated))
        val uri = Files.readAllLines(Path.of(fixture)).first { it.startsWith("vless://", true) }.trim()
        val profile = VpnProfileConfig(type = "vless", uri = uri)
        val entries = VpnEntryFailoverConfig(VpnEntryEndpoint("178.208.70.181", 443, "RuWeb"),
            VpnEntryEndpoint("48.103.218.29", 443, "Justhost"))
        // Keep the Windows Unix-domain socket below the core's 100-byte path limit.
        val runtime = Files.createTempDirectory("efl-")
        val diagnostics = isolated.resolve("desktop-entry-live.private.log")
        val owner = DesktopEntryFailoverProcess()
        val settings = DesktopSocksProxySettings(port = LocalPortAllocator.select("127.0.0.1", 11960),
            username = "synthetic-live", password = UUID.randomUUID().toString().replace("-", ""), blockAds = false)
        try {
            val process = owner.start(profile, settings, entries, DesktopPhysicalInterface.currentWindows(),
                shouldContinue = { true }, log = { source, line ->
                    // This private file is never attached to assertions or printed by the test runner.
                    Files.writeString(diagnostics, "$source $line\n", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND)
                }, runtimeDirectory = runtime)
            assertTrue(process.isAlive && owner.isRunning)
            withTimeout(45_000) { while (owner.selectedEntry == null) delay(50) }
            val selected = assertNotNull(owner.selectedEntry)
            System.getenv("UNIFIEDVPN_ENTRY_EXPECTED")?.let { expected ->
                assertEquals(VpnEntryChoice.valueOf(expected), selected)
            }
            val curl = ProcessBuilder("C:/Windows/System32/curl.exe", "--silent", "--show-error", "--max-time", "20",
                "--noproxy", "", "--socks5-hostname", "127.0.0.1:${settings.port}",
                "--proxy-user", "${settings.username}:${settings.password}",
                "https://www.cloudflare.com/cdn-cgi/trace").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val trace = try {
                assertTrue(curl.waitFor(25, TimeUnit.SECONDS), "The selected entry did not finish the verified HTTPS request")
                assertEquals(0, curl.exitValue(), "The selected entry did not complete verified HTTPS")
                curl.inputStream.bufferedReader().readText()
            } finally { if (curl.isAlive) curl.destroyForcibly() }
            val exitIp = assertNotNull(trace.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip="))
            val expectedExits = System.getenv("UNIFIEDVPN_ENTRY_EXPECTED_EGRESS")?.split(',')?.map(String::trim)?.toSet()
                ?: setOf("222.167.216.175", "89.125.250.176")
            assertTrue(exitIp in expectedExits, "Verified HTTPS did not use an expected managed VPN exit")
            assertTrue(trace.lineSequence().any { it == "tls=TLSv1.3" || it == "tls=TLSv1.2" })
            Files.writeString(isolated.resolve("desktop-entry-live-proof.json"),
                """{"selectedEntry":"${selected.name}","exitIp":"$exitIp","verifiedHttps":true,"osProxyAndTunChanged":false}""")
            owner.close()
            assertFalse(process.isAlive)
            assertFalse(owner.isRunning)
            assertTrue(Files.list(runtime).use { it.findAny().isEmpty }, "Private native resources remain after close")
        } finally {
            owner.close()
            Files.deleteIfExists(runtime)
        }
    }
}
