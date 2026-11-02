package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.WindowsTrustedExecutables
import org.olcbox.app.vpn.ConnectionPreflight
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Read-only checks: never stop another VPN or change its adapters/routes. */
internal object DesktopConnectionPreflight {
    suspend fun requireNoActiveVpn() {
        val active = try {
            if (DesktopPaths.os == DesktopOs.Windows) {
                activeVpnNames(readWindowsSnapshot())
            } else {
                withContext(Dispatchers.IO) {
                    NetworkInterface.getNetworkInterfaces().toList().filter { network ->
                        network.isUp && !network.isLoopback &&
                            network.inetAddresses.toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress } &&
                            (isVpnName(network.name + " " + network.displayName) ||
                                Files.exists(Path.of("/sys/class/net", network.name, "tun_flags")))
                    }.map { it.name }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error(ConnectionPreflight.CHECK_FAILED)
        }
        check(active.isEmpty()) { ConnectionPreflight.VPN_ACTIVE }
    }

    @Serializable
    internal data class Adapter(
        val name: String,
        val description: String,
        val type: Int,
        val up: Boolean,
        val hasAddress: Boolean
    )

    @Serializable
    internal data class Snapshot(val adapters: List<Adapter>)

    internal fun activeVpnNames(snapshot: Snapshot): List<String> = snapshot.adapters.filter { adapter ->
        val identity = adapter.name + " " + adapter.description
        adapter.up && adapter.hasAddress &&
            (isVpnName(identity) || (adapter.type == 23 && !identity.contains("PPPoE", ignoreCase = true)))
    }.map { it.name }

    private fun isVpnName(identity: String): Boolean = vpnName.containsMatchIn(identity)

    // Match VPN drivers and conventional interface names, not all virtual adapters:
    // Hyper-V, VirtualBox and WSL can be up without any VPN connection.
    private val vpnName = Regex(
        "amnezia|wireguard|wintun|openvpn|tap-windows|ovpn|tailscale|zerotier|" +
            "nordlynx|protonvpn|mullvad|fortinet|forticlient|anyconnect|globalprotect|" +
            "vpn|(?:^|[^a-z0-9])(?:awg|wg|tun|tap|utun|ppp|ipsec)\\d*(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE
    )

    internal fun windowsCommand(powershell: String): List<String> {
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            ${'$'}adapters = @([System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | ForEach-Object {
                ${'$'}addresses = @(${'$'}_.GetIPProperties().UnicastAddresses | Where-Object {
                    -not [System.Net.IPAddress]::IsLoopback(${'$'}_.Address) -and
                    -not ${'$'}_.Address.IsIPv6LinkLocal -and
                    ${'$'}_.Address.ToString() -notmatch '^169\.254\.' -and
                    ${'$'}_.Address.ToString() -notin @('0.0.0.0', '::')
                })
                @{
                    name = ${'$'}_.Name; description = ${'$'}_.Description; type = [int]${'$'}_.NetworkInterfaceType
                    up = (${'$'}_.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up)
                    hasAddress = (${'$'}addresses.Count -gt 0)
                }
            })
            @{ adapters = ${'$'}adapters } | ConvertTo-Json -Depth 4 -Compress
        """.trimIndent()
        return listOf(powershell, "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
    }

    internal suspend fun readWindowsSnapshot(): Snapshot = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(windowsCommand(WindowsTrustedExecutables.powerShellPath().toString()))
            .redirectErrorStream(true).start()
        try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            fun drain(toEnd: Boolean = false) {
                while (toEnd || process.inputStream.available() > 0) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    check(output.size() <= 128 * 1024) { ConnectionPreflight.CHECK_FAILED }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                drain()
                check(System.nanoTime() < deadline) { ConnectionPreflight.CHECK_FAILED }
                delay(25)
            }
            drain(toEnd = true)
            check(process.exitValue() == 0) { ConnectionPreflight.CHECK_FAILED }
            Json.decodeFromString<Snapshot>(output.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF"))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }
}
