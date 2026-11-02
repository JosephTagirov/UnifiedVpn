package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.ConnectionPreflight
import org.olcbox.app.vpn.DesktopRoutingMode
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.VpnStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopConnectionPreflightTest {
    @Test fun readsWindowsAdaptersInIsolatedPowerShellUserData() = runBlocking {
        if (DesktopPaths.os != DesktopOs.Windows) return@runBlocking
        val snapshot = DesktopConnectionPreflight.readWindowsSnapshot()
        assertTrue(snapshot.adapters.isNotEmpty())
    }

    @Test fun detectsActiveAmneziaAndRenamedWireguardAdapters() {
        val adapters = listOf(
            DesktopConnectionPreflight.Adapter("AmneziaVPN", "AmneziaVPN Tunnel", 53, true, true),
            DesktopConnectionPreflight.Adapter("Custom name", "WireGuard Tunnel", 53, true, true),
            DesktopConnectionPreflight.Adapter("Work", "WAN Miniport (IKEv2)", 23, true, true)
        )
        assertEquals(listOf("AmneziaVPN", "Custom name", "Work"),
            DesktopConnectionPreflight.activeVpnNames(DesktopConnectionPreflight.Snapshot(adapters)))
    }

    @Test fun ignoresDisconnectedVpnAndVirtualMachineAdapters() {
        val adapters = listOf(
            DesktopConnectionPreflight.Adapter("AmneziaVPN", "AmneziaVPN Tunnel", 53, false, true),
            DesktopConnectionPreflight.Adapter("wg0", "WireGuard Tunnel", 53, true, false),
            DesktopConnectionPreflight.Adapter("vEthernet (Default Switch)", "Hyper-V Virtual Ethernet Adapter", 6, true, true),
            DesktopConnectionPreflight.Adapter("Ethernet 4", "VirtualBox Host-Only Ethernet Adapter", 6, true, true),
            DesktopConnectionPreflight.Adapter("Wi-Fi", "Wireless LAN Card", 71, true, true),
            DesktopConnectionPreflight.Adapter("Broadband", "WAN Miniport (PPPoE)", 23, true, true)
        )
        assertTrue(DesktopConnectionPreflight.activeVpnNames(DesktopConnectionPreflight.Snapshot(adapters)).isEmpty())
    }

    @Test fun proxyDetectionIgnoresDisabledSavedServerButRecognizesManualAndPac() {
        assertFalse(WindowsProxyState("0x0", "old-proxy:8080", null, "").isActive())
        assertFalse(WindowsProxyState(null, null, null, null).isActive())
        assertTrue(WindowsProxyState("0x1", "localhost:8080", null, null).isActive())
        assertTrue(WindowsProxyState("1", "localhost:8080", null, null).isActive())
        assertTrue(WindowsProxyState("0", null, null, "https://work.example/proxy.pac").isActive())
    }

    @Test fun autoChoosesSystemProxyOrSocksWithoutChangingExplicitChoices() {
        val plain = DesktopSocksProxySettings()
        assertEquals(DesktopRoutingMode.SystemProxy, plain.resolvedRoutingMode(DesktopOs.Windows, true, false))
        assertEquals(DesktopRoutingMode.LocalSocks, plain.resolvedRoutingMode(DesktopOs.Windows, true, true))
        assertEquals(DesktopRoutingMode.LocalSocks,
            plain.copy(username = "user", password = "password").resolvedRoutingMode(DesktopOs.Windows, true, false))
        for (mode in listOf(DesktopRoutingMode.LocalSocks, DesktopRoutingMode.SystemProxy)) {
            assertEquals(mode, plain.copy(routingMode = mode).resolvedRoutingMode(DesktopOs.Windows, true, true))
        }
    }

    @Test fun autoKeepsFullVpnForAwgAndVlessAndRespectsManualOverrides() {
        val settings = DesktopSocksProxySettings(username = "user", password = "password")
        assertEquals(DesktopRoutingMode.Tun, settings.resolvedRoutingMode(DesktopOs.Windows, false, false))
        assertEquals(DesktopRoutingMode.LocalSocks, settings.resolvedRoutingMode(DesktopOs.Windows, false, true))
        assertEquals(DesktopRoutingMode.Tun, settings.resolvedRoutingMode(DesktopOs.Linux, false, false))
        assertEquals(DesktopRoutingMode.LocalSocks,
            settings.copy(externalRoutingMode = DesktopRoutingMode.LocalSocks).resolvedRoutingMode(DesktopOs.Windows, false, false))
    }

    @Test fun activeVpnStopsEveryModeBeforeStartingAnEngineOrTouchingProxy() = runBlocking {
        val dir = Files.createTempDirectory("connection-preflight-")
        val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(dir))
        val profiles = buildList {
            add(LocationEntry.from("test", LocationConfig("Test", "room", "a".repeat(64))))
            if (DesktopPaths.os == DesktopOs.Windows) {
                add(LocationEntry.fromProfile("test", VpnProfileConfig(type = VpnProfileConfig.TYPE_VLESS,
                    uri = "vless://00000000-0000-4000-8000-000000000001@vpn.example:443?security=tls&type=tcp")))
                add(LocationEntry.fromProfile("test", VpnProfileConfig(type = VpnProfileConfig.TYPE_AMNEZIA_WG,
                    rawConfig = """
                        [Interface]
                        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                        Address = 10.77.0.2/32
                        [Peer]
                        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
                        Endpoint = vpn.example:51820
                        AllowedIPs = 0.0.0.0/0
                    """.trimIndent())))
            }
        }
        try {
            val cases = profiles.flatMap { profile -> DesktopRoutingMode.entries.map { profile to it } }
            for ((profile, mode) in cases) {
                repository.saveBundle(LocationBundleV4(activeLocationId = "test", locations = listOf(profile)))
                var proxyReads = 0
                var proxyWrites = 0
                val proxy = object : DesktopProxyController {
                    override suspend fun hasActiveProxy(): Boolean { proxyReads++; return false }
                    override suspend fun enable(pacUrl: String) { proxyWrites++ }
                    override suspend fun restore() { proxyWrites++ }
                }
                val manager = DesktopVpnManager(repository, proxyController = proxy,
                    checkActiveVpn = { error(ConnectionPreflight.VPN_ACTIVE) })
                try {
                    manager.updateSocksProxySettings(DesktopSocksProxySettings(routingMode = mode, externalRoutingMode = mode))
                    manager.startVpn()
                    val status = withTimeout(5_000) { manager.status.first { it is VpnStatus.Error } }
                    assertEquals(VpnStatus.Error(ConnectionPreflight.VPN_ACTIVE), status)
                    assertEquals(0, proxyReads)
                    assertEquals(0, proxyWrites)
                    assertFalse(manager.isConnected.value)
                    assertNull(manager.activeRoutingMode.value)
                    assertNull(manager.subscriptionFetchProxy())
                    assertFalse(manager.logs.value.any { it.contains("process started", true) })
                } finally { manager.close() }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
