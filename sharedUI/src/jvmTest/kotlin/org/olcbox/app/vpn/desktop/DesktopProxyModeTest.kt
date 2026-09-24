package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.vpn.DesktopRoutingMode
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.desktopOlcRtcStartupFailure
import org.olcbox.app.vpn.isDesktopEngineReady
import org.olcbox.app.vpn.olcRtcNativeLibrarySpec
import org.olcbox.app.vpn.usesProxyRoutingSettings
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

class DesktopProxyModeTest {

    @Test
    fun windowsKeepsTunForExternalProfilesAndBlocksItForProxyProfiles() {
        assertEquals(
            DesktopRoutingMode.SystemProxy,
            DesktopRoutingMode.SystemProxy.resolveFor(DesktopOs.Windows)
        )
        assertEquals(
            DesktopRoutingMode.Tun,
            DesktopRoutingMode.Tun.resolveFor(DesktopOs.Windows)
        )
        assertEquals(
            DesktopRoutingMode.SystemProxy,
            DesktopRoutingMode.Auto.resolveFor(DesktopOs.Windows)
        )
        assertEquals(
            DesktopRoutingMode.LocalSocks,
            DesktopRoutingMode.Auto.resolveFor(
                os = DesktopOs.Windows,
                usesProxySettings = true
            )
        )
        assertEquals(DesktopRoutingMode.LocalSocks, DesktopRoutingMode.Tun.resolveFor(DesktopOs.Windows, true))
        assertEquals(DesktopRoutingMode.SystemProxy, DesktopRoutingMode.SystemProxy.resolveFor(DesktopOs.Windows, true))
    }

    @Test
    fun olcRtcAndOpenFluxShareProxyModesIndependentlyOfVlessAndAwg() {
        val settings = DesktopSocksProxySettings(
            routingMode = DesktopRoutingMode.SystemProxy,
            externalRoutingMode = DesktopRoutingMode.Tun
        ).normalizedFor(DesktopOs.Windows)
        for (type in listOf(VpnProfileConfig.TYPE_OLCRTC, VpnProfileConfig.TYPE_OPENFLUX)) {
            val group = VpnProfileConfig(type = type).usesProxyRoutingSettings(DesktopOs.Windows)
            assertTrue(group)
            assertEquals(DesktopRoutingMode.SystemProxy, settings.routingModeFor(group))
            assertTrue(DesktopRoutingMode.Tun !in DesktopRoutingMode.availableFor(DesktopOs.Windows, group))
        }
        for (type in listOf(VpnProfileConfig.TYPE_VLESS, VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)) {
            val group = VpnProfileConfig(type = type).usesProxyRoutingSettings(DesktopOs.Windows)
            assertTrue(!group)
            assertEquals(DesktopRoutingMode.Tun, settings.routingModeFor(group))
            assertTrue(DesktopRoutingMode.Tun in DesktopRoutingMode.availableFor(DesktopOs.Windows, group))
        }
    }

    @Test
    fun oldTunSettingCannotReachOpenFluxOrOlcRtcOnWindows() {
        val settings = DesktopSocksProxySettings(
            routingMode = DesktopRoutingMode.Tun, externalRoutingMode = DesktopRoutingMode.Tun
        ).normalizedFor(DesktopOs.Windows)
        assertEquals(DesktopRoutingMode.Auto, settings.routingMode)
        assertEquals(DesktopRoutingMode.LocalSocks, settings.routingMode.resolveFor(DesktopOs.Windows, true))
        assertEquals(DesktopRoutingMode.Tun, settings.externalRoutingMode)
    }

    @Test
    fun linuxRoutingBehaviorIsUnchanged() {
        assertEquals(DesktopRoutingMode.Tun, DesktopRoutingMode.Tun.resolveFor(DesktopOs.Linux, true))
        assertEquals(DesktopRoutingMode.LocalSocks, DesktopRoutingMode.Auto.resolveFor(DesktopOs.Linux, true))
        assertEquals(DesktopRoutingMode.Tun, DesktopRoutingMode.Auto.resolveFor(DesktopOs.Linux, false))
        assertTrue(!VpnProfileConfig(type = VpnProfileConfig.TYPE_OPENFLUX).usesProxyRoutingSettings(DesktopOs.Linux))
    }

    @Test
    fun desktopRoutingModesAlwaysOfferAutoAndLocalSocks() {
        val available = DesktopRoutingMode.availableForCurrentPlatform()

        assertTrue(DesktopRoutingMode.Auto in available)
        assertTrue(DesktopRoutingMode.LocalSocks in available)
        assertEquals(
            DesktopRoutingMode.LocalSocks,
            DesktopSocksProxySettings(routingMode = DesktopRoutingMode.LocalSocks)
                .normalized()
                .routingMode
        )
    }

    @Test
    fun pacRoutesLocalTrafficDirectAndEverythingElseThroughSocks() {
        val pac = PacServer.generatePac("127.0.0.1", 10808)

        assertContains(pac, "isPlainHostName(host)")
        assertContains(pac, "host === \"localhost\"")
        assertContains(pac, "SOCKS5 127.0.0.1:10808; SOCKS 127.0.0.1:10808")
    }

    @Test
    fun pacServerUpdatesSocksTargetWhileAlreadyRunning() {
        val server = PacServer(port = 0)

        server.start("127.0.0.1", 10808)
        server.start("127.0.0.1", 10810, "user", "pass")

        val pac = server.currentPacContent()
        assertContains(pac, "SOCKS5 user:pass@127.0.0.1:10810; SOCKS user:pass@127.0.0.1:10810")
        assertTrue("SOCKS5 127.0.0.1:10808" !in pac)

        server.stop()
    }

    @Test
    fun localPortAllocatorSkipsAnOccupiedPreferredPort() {
        ServerSocket().use { occupied ->
            occupied.bind(InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, 0))

            val selected = LocalPortAllocator.select(
                host = PacServer.LOCAL_SOCKS_HOST,
                preferredPort = occupied.localPort
            )

            assertTrue(selected != occupied.localPort)
            assertTrue(LocalPortAllocator.canBind(PacServer.LOCAL_SOCKS_HOST, selected))
        }
    }

    @Test
    fun olcRtcReadinessIgnoresAnUnrelatedOpenSocksPort() {
        assertTrue(
            !isDesktopEngineReady(
                readinessSignalReceived = false,
                portAcceptsConnections = true,
                requireReadinessSignal = true
            )
        )
        assertTrue(
            isDesktopEngineReady(
                readinessSignalReceived = true,
                portAcceptsConnections = false,
                requireReadinessSignal = true
            )
        )
        assertTrue(
            isDesktopEngineReady(
                readinessSignalReceived = false,
                portAcceptsConnections = true,
                requireReadinessSignal = false
            )
        )
    }

    @Test
    fun olcRtcHandshakeTimeoutExplainsTheServerSideProblem() {
        val message = desktopOlcRtcStartupFailure(
            "client: handshake: handshake client: read welcome: handshake: read hdr: timeout"
        )

        assertContains(message.orEmpty(), "server did not answer")
        assertContains(message.orEmpty(), "same olcRTC protocol version")
        assertEquals(null, desktopOlcRtcStartupFailure("[ice] INFO: connection state connected"))
    }

    @Test
    fun pacServerFallsBackWhenItsPreferredPortIsOccupied() {
        ServerSocket().use { occupied ->
            occupied.bind(InetSocketAddress(PacServer.PAC_HOST, 0))
            val server = PacServer(port = occupied.localPort)
            try {
                server.start(PacServer.LOCAL_SOCKS_HOST, 10810)

                assertTrue(server.boundPort != occupied.localPort)
                assertContains(server.url, ":${server.boundPort}/proxy.pac")
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun olcRtcCryptoKeyRequiresExactly64HexCharacters() {
        assertTrue(LocationConfig.isValidCryptoKey("a".repeat(64)))
        assertTrue(LocationConfig.isValidCryptoKey("A1".repeat(32)))
        assertTrue(!LocationConfig.isValidCryptoKey("a".repeat(63)))
        assertTrue(!LocationConfig.isValidCryptoKey("z".repeat(64)))
    }

    @Test
    fun pacEscapesSocksCredentialsInUserInfo() {
        val pac = PacServer.generatePac(
            socksHost = "127.0.0.1",
            socksPort = 10808,
            socksUsername = "user name",
            socksPassword = "p@ss:word"
        )

        assertContains(
            pac,
            "SOCKS5 user%20name:p%40ss%3Aword@127.0.0.1:10808; " +
                    "SOCKS user%20name:p%40ss%3Aword@127.0.0.1:10808"
        )
    }

    @Test
    fun olcRtcCommandUsesLocationProviderRoomAndKey() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val expectedTransport = LocationConfig.normalizeTransport(
                LocationConfig.DEFAULT_TRANSPORT,
                provider
            )
            val binary = Path.of("/tmp/olcrtc")
            val configPath = Path.of("/tmp/client.yaml")
            val command = OlcRtcCommand(
                binary = binary,
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808,
                dnsServer = "192.168.43.1:53"
            )
            val args = command.args(configPath)
            val yaml = command.yaml()

            assertEquals(listOf(binary.toString(), configPath.toString()), args)
            assertContains(yaml, "mode: cnc")
            assertContains(yaml, "provider: '${OlcRtcCommand.desktopProviderArg(provider)}'")
            assertContains(yaml, "transport: '$expectedTransport'")
            assertContains(yaml, "id: 'room-$provider'")
            assertContains(yaml, "port: 10808")
            assertContains(yaml, "dns: '192.168.43.1:53'")
            assertTrue("insecure_skip_verify" !in yaml)
            assertTrue("jitsi:" !in yaml)
            if (expectedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                assertContains(yaml, "vp8:")
                assertContains(yaml, "fps: 60")
                assertContains(yaml, "batch_size: 64")
            }
            assertTrue("client-id" !in yaml)
            assertTrue("link:" !in yaml)
            assertTrue("data:" !in yaml)
        }
    }

    @Test
    fun olcRtcCommandAllowsDatachannelForNonTelemostProviders() {
        val dataDir = Path.of("/tmp/olcbox-data")
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dnsServer = "192.168.43.1:53",
            dataDir = dataDir
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_DATACHANNEL}'")
        assertTrue("vp8:" !in command)
        assertContains(command, "data: '${dataDir}'")
    }

    @Test
    fun olcRtcCommandAddsSeiDefaults() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "Telemost",
                id = "room",
                key = "c".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                transport = LocationConfig.TRANSPORT_SEICHANNEL
            ),
            dnsServer = "192.168.43.1:53"
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_SEICHANNEL}'")
        assertContains(command, "sei:")
        assertContains(command, "fps: 60")
        assertContains(command, "batch_size: 64")
        assertContains(command, "fragment_size: 900")
        assertContains(command, "ack_timeout_ms: 2000")
        assertTrue("vp8:" !in command)
    }

    @Test
    fun nativeLibrarySpecSelectsPlatformFiles() {
        assertEquals(
            "libolcrtc-linux-amd64.so",
            olcRtcNativeLibrarySpec("Linux", "x86_64")?.fileName
        )
        assertEquals(
            "olcrtc-windows-amd64.dll",
            olcRtcNativeLibrarySpec("Windows 11", "amd64")?.fileName
        )
    }

    @Test
    fun linuxTunConfigCanRunRouteScriptsInsidePrivilegedTunnelProcess() {
        val config = LinuxTunController.configContent(
            socksPort = 10810,
            socksUsername = "user'name",
            socksPassword = "p@ss:word",
            postUpScript = "/tmp/olcbox-up.sh",
            preDownScript = "/tmp/olcbox-down.sh"
        )

        assertContains(config, "port: 10810")
        assertContains(config, "username: 'user''name'")
        assertContains(config, "password: 'p@ss:word'")
        assertContains(config, "post-up-script: /tmp/olcbox-up.sh")
        assertContains(config, "pre-down-script: /tmp/olcbox-down.sh")
    }

    @Test
    fun olcRtcCommandUsesDesktopWbStreamProviderAlias() {
        listOf(LocationConfig.PROVIDER_WB_STREAM, "wbstream").forEach { provider ->
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig(
                    name = "WB",
                    id = "room-wb",
                    key = "b".repeat(64),
                    bypassProvider = provider
                ),
                dnsServer = "192.168.43.1:53"
            ).yaml()

            assertContains(command, "provider: 'wbstream'")
        }
    }

    @Test
    fun windowsProxyCommandsBackupShapeIsRestorable() {
        val trustedReg = "C:\\Windows\\System32\\reg.exe"
        val enable = WindowsProxyController.enableCommands(
            "http://127.0.0.1:10809/proxy.pac",
            trustedReg
        )
        assertEquals(trustedReg, enable.first().first())
        assertContains(enable.flatten(), "AutoConfigURL")
        assertContains(enable.flatten(), "http://127.0.0.1:10809/proxy.pac")

        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = "127.0.0.1:8888",
                proxyOverride = "<local>",
                autoConfigUrl = null
            ),
            trustedReg
        )

        assertContains(restore.flatten(), "ProxyEnable")
        assertContains(restore.flatten(), "ProxyServer")
        assertContains(restore.flatten(), "ProxyOverride")
        assertContains(restore.flatten(), "AutoConfigURL")
        assertContains(restore.flatten(), "delete")
    }

    @Test
    fun windowsProxyRefreshCommandUsesFullyQualifiedWinInetSignature() {
        val trustedPowerShell =
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        val refresh = WindowsProxyController.refreshCommand(trustedPowerShell)
        val script = refresh.last()

        assertEquals(trustedPowerShell, refresh.first())
        assertContains(refresh, "-NonInteractive")
        assertContains(script, "System.Runtime.InteropServices.DllImport")
        assertContains(script, "System.IntPtr")
        assertContains(script, "InternetSetOption")
    }

    @Test
    fun linuxTunConfigUsesLocalSocksAndIpv4MapDns() {
        val config = LinuxTunController.configContent()

        assertContains(config, "name: olcbox0")
        assertContains(config, "ipv4: 10.0.88.88")
        assertContains(config, "address: 127.0.0.1")
        assertContains(config, "port: 10808")
        assertContains(config, "udp: 'tcp'")
        assertContains(config, "mapdns:")
        assertContains(config, "network: 100.64.0.0")
        assertContains(config, "task-stack-size: 86016")
        assertContains(config, "tcp-buffer-size: 65536")
    }

    @Test
    fun windowsTunPassesCredentialsAsJsonWithNoExecutableOrCommandField() {
        val config = Json.parseToJsonElement(WindowsTunController.helperConfiguration(
            10812, "user name", "p@ss:\"word", listOf("203.0.113.7")
        )).jsonObject
        assertEquals(setOf("port", "username", "password", "endpoints"), config.keys)
        assertEquals("10812", config.getValue("port").jsonPrimitive.content)
        assertEquals("user name", config.getValue("username").jsonPrimitive.content)
        assertEquals("p@ss:\"word", config.getValue("password").jsonPrimitive.content)
        assertEquals("203.0.113.7", config.getValue("endpoints").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun windowsTunRejectsInvalidLocalSettingsBeforeLaunchingUac() {
        assertFailsWith<IllegalArgumentException> { WindowsTunController.helperConfiguration(80, "", "", listOf("203.0.113.7")) }
        assertFailsWith<IllegalArgumentException> { WindowsTunController.helperConfiguration(10808, "", "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { WindowsTunController.helperConfiguration(10808, "x".repeat(65), "", listOf("203.0.113.7")) }
    }

    @Test
    fun linuxTunScriptsRouteUserTrafficThroughTunAndKeepRootDirect() {
        val up = LinuxTunController.upScriptContent()
        val down = LinuxTunController.downScriptContent()

        assertContains(up, "ip rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip route add default dev olcbox0 table 51820")
        assertContains(up, "ip rule add lookup 51820 pref 20")
        assertContains(up, "resolvectl dns olcbox0 1.1.1.1")
        assertContains(up, "for setting in /proc/sys/net/ipv4/conf/*/rp_filter")
        assertContains(up, "printf '0\\n' > \"${'$'}setting\"")
        assertContains(down, "ip rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip route flush table 51820")
        assertContains(down, "resolvectl revert olcbox0")
        assertContains(down, "done < \"${'$'}rp_filter_state\"")
    }

    @Test
    fun linuxDnsResolverUsesActiveUpstreamInsteadOfSystemdStub() {
        val dns = DesktopDnsResolver.selectLinuxDnsServer(
            resolvectlOutput = "Link 3 (wlan0): 192.168.43.1 2a00:1234::53",
            nmcliOutput = "192.168.43.1",
            resolvConf = "nameserver 127.0.0.53"
        )

        assertEquals("192.168.43.1:53", dns)
    }

    @Test
    fun linuxDnsResolverFallsBackToLocalSystemResolver() {
        val dns = DesktopDnsResolver.selectLinuxDnsServer(
            resolvectlOutput = "",
            nmcliOutput = "",
            resolvConf = "nameserver 127.0.0.53"
        )

        assertEquals("127.0.0.53:53", dns)
    }

    @Test
    fun linuxDnsResolverFindsPhysicalDefaultRoute() {
        val interfaceName = DesktopDnsResolver.defaultRouteInterface(
            """
            default dev olcbox0 metric 5
            default via 192.168.43.1 dev wlan0 proto dhcp metric 600
            """.trimIndent()
        )

        assertEquals("wlan0", interfaceName)
    }

    @Test
    fun windowsDnsResolverUsesPhysicalAdapterDnsAndSkipsLoopback() {
        val dns = DesktopDnsResolver.selectWindowsDnsServer(
            "127.0.0.1\r\n192.168.1.1\r\n"
        )

        assertEquals("192.168.1.1:53", dns)
    }

    @Test
    fun windowsDnsResolverUsesProvidedTrustedPowerShell() {
        val trustedPowerShell =
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        val command = DesktopDnsResolver.windowsDnsCommand(trustedPowerShell)

        assertEquals(trustedPowerShell, command.first())
        assertContains(command, "-NonInteractive")
        assertContains(command.last(), "Get-DnsClientServerAddress")
    }
}
