package org.olcbox.app.vpn.desktop

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.desktop.WindowsTrustedExecutables

/** Resolve the physical upstream before installing any VPN capture routes. */
internal object DesktopPhysicalInterface {
    // A cold Windows networking/CIM module can take over 8 seconds to load under system load.
    private const val DISCOVERY_TIMEOUT_SECONDS = 30L

    fun currentWindows(): String = currentWindowsNetwork().alias

    fun currentWindowsNetwork(): PhysicalUpstream {
        val process = ProcessBuilder(command(WindowsTrustedExecutables.powerShellPath().toString()))
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Physical network discovery timed out" }
            check(process.exitValue() == 0) { "Physical network discovery failed" }
            return parseNetwork(process.inputStream.bufferedReader(Charsets.UTF_8).readText())
                ?: error("No physical network interface is available for direct routing")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    internal fun parse(output: String): String? = runCatching {
        Json.parseToJsonElement(output.trim().removePrefix("\uFEFF")).jsonPrimitive.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none { char -> char.code < 32 || char.code == 127 } }
    }.getOrNull()

    internal fun parseNetwork(output: String): PhysicalUpstream? = runCatching {
        val value = Json.parseToJsonElement(output.trim().removePrefix("\uFEFF")).jsonObject
        require(value.keys == setOf("Alias", "Index", "IPv4Address"))
        val alias = value.getValue("Alias").jsonPrimitive.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none { char -> char.code < 32 || char.code == 127 } }
            ?: return null
        val index = value.getValue("Index").jsonPrimitive.takeIf { !it.isString }?.intOrNull
            ?.takeIf { it > 0 } ?: return null
        val address = value.getValue("IPv4Address").jsonPrimitive.takeIf { it.isString }?.contentOrNull
            ?.takeIf { physicalIpv4Bytes(it) != null } ?: return null
        PhysicalUpstream(alias, index, address)
    }.getOrNull()

    internal fun command(powershell: String): List<String> {
        require(powershell.isNotBlank())
        val script = """
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            ${'$'}physical = @(Get-NetAdapter -Physical | Where-Object Status -eq Up | ForEach-Object ifIndex)
            ${'$'}addresses = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
              Where-Object { ${'$'}_.AddressState -eq 'Preferred' -and -not ${'$'}_.SkipAsSource })
            ${'$'}candidates = @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
              Where-Object { ${'$'}_.InterfaceIndex -in ${'$'}physical } | ForEach-Object {
                ${'$'}route = ${'$'}_
                ${'$'}metric = (Get-NetIPInterface -AddressFamily IPv4 -InterfaceIndex ${'$'}route.InterfaceIndex -ErrorAction Stop).InterfaceMetric
                ${'$'}address = ${'$'}addresses | Where-Object InterfaceIndex -eq ${'$'}route.InterfaceIndex |
                  Sort-Object IPAddress | Select-Object -First 1
                if (${'$'}null -ne ${'$'}address) {
                  [PSCustomObject]@{ Alias = ${'$'}route.InterfaceAlias; Metric = [int]${'$'}route.RouteMetric + [int]${'$'}metric; Index = [int]${'$'}route.InterfaceIndex; IPv4Address = ${'$'}address.IPAddress }
                }
              })
            ${'$'}selected = ${'$'}candidates | Sort-Object Metric, Index | Select-Object -First 1
            if (${'$'}null -eq ${'$'}selected) { exit 2 }
            ${'$'}selected | Select-Object Alias, Index, IPv4Address | ConvertTo-Json -Compress
        """.trimIndent()
        // Go's Windows net.Interface.Name is the adapter FriendlyName / PowerShell InterfaceAlias.
        return listOf(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
    }
}
internal data class PhysicalUpstream(val alias: String, val index: Int, val ipv4Address: String)


/** Parse a usable physical IPv4 source without invoking the system DNS resolver. */
internal fun physicalIpv4Bytes(value: String): ByteArray? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part ->
        val number = part.toIntOrNull() ?: return null
        if (number !in 0..255 || number.toString() != part) return null
        number
    }
    if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224 || (octets[0] == 169 && octets[1] == 254)) return null
    return octets.map(Int::toByte).toByteArray()
}
