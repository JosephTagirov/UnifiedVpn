package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object DesktopDnsResolver {
    const val FALLBACK_DNS_SERVER = "1.1.1.1:53"

    fun current(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Linux -> currentLinuxDnsServer() ?: FALLBACK_DNS_SERVER
            DesktopOs.Windows -> currentWindowsDnsServer() ?: FALLBACK_DNS_SERVER
            DesktopOs.MacOS,
            DesktopOs.Other -> FALLBACK_DNS_SERVER
        }
    }

    private fun currentWindowsDnsServer(): String? {
        val script = """
            ${'$'}routes = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.InterfaceAlias -ne '${WindowsTunController.TUN_NAME}' } |
              Sort-Object RouteMetric, InterfaceMetric
            foreach (${'$'}route in ${'$'}routes) {
              ${'$'}servers = (Get-DnsClientServerAddress -InterfaceIndex ${'$'}route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses
              if (${'$'}servers) { ${'$'}servers; break }
            }
        """.trimIndent()
        val output = runCommand(
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script
            )
        ).orEmpty()
        return selectWindowsDnsServer(output)
    }

    private fun currentLinuxDnsServer(): String? {
        val defaultRouteOutput = runCommand(listOf("ip", "route", "show", "default")).orEmpty()
        val interfaceName = defaultRouteInterface(defaultRouteOutput)

        val resolvectlOutput = if (interfaceName != null) {
            runCommand(listOf("resolvectl", "dns", interfaceName))
        } else {
            runCommand(listOf("resolvectl", "dns"))
        }
        val nmcliOutput = interfaceName?.let {
            runCommand(listOf("nmcli", "-g", "IP4.DNS,IP6.DNS", "device", "show", it))
        }
        val resolvConf = runCatching {
            Files.readString(Path.of("/etc/resolv.conf"))
        }.getOrDefault("")

        return selectLinuxDnsServer(
            resolvectlOutput = resolvectlOutput.orEmpty(),
            nmcliOutput = nmcliOutput.orEmpty(),
            resolvConf = resolvConf
        )
    }

    private fun runCommand(command: List<String>): String? {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    internal fun defaultRouteInterface(output: String): String? {
        return output.lineSequence()
            .filter { it.trimStart().startsWith("default ") }
            .mapNotNull { line ->
                DEFAULT_ROUTE_DEVICE.find(line)?.groupValues?.getOrNull(1)
            }
            .firstOrNull { it != LinuxTunController.TUN_NAME }
    }

    internal fun selectLinuxDnsServer(
        resolvectlOutput: String,
        nmcliOutput: String,
        resolvConf: String
    ): String? {
        val candidates = buildList {
            addAll(ipAddresses(resolvectlOutput))
            addAll(ipAddresses(nmcliOutput))
            addAll(resolvConfNameservers(resolvConf))
        }.distinct()

        val selected = candidates.firstOrNull { !isLoopback(it) }
            ?: candidates.firstOrNull()
            ?: return null
        return dnsEndpoint(selected)
    }

    internal fun selectWindowsDnsServer(output: String): String? {
        val candidates = ipAddresses(output).distinct()
        val selected = candidates.firstOrNull { !isLoopback(it) }
            ?: candidates.firstOrNull()
            ?: return null
        return dnsEndpoint(selected)
    }

    private fun ipAddresses(output: String): List<String> {
        return output.lineSequence()
            .flatMap { it.splitToSequence(Regex("\\s+")) }
            .mapNotNull(::ipLiteralOrNull)
            .toList()
    }

    private fun resolvConfNameservers(content: String): List<String> {
        return content.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("nameserver ") }
            .mapNotNull { line -> ipLiteralOrNull(line.substringAfter("nameserver ").trim()) }
            .toList()
    }

    private fun ipLiteralOrNull(token: String): String? {
        val candidate = token
            .trim()
            .trim(',', ';')
            .substringBefore('#')
        if (candidate.isEmpty() || ('.' !in candidate && ':' !in candidate)) return null

        val addressWithoutZone = candidate.substringBefore('%')
        val parsed = runCatching { InetAddress.getByName(addressWithoutZone) }.getOrNull()
            ?: return null
        if (':' in candidate && parsed.hostAddress?.contains(':') != true) return null
        if ('.' in candidate && ':' !in candidate && parsed.hostAddress?.contains('.') != true) return null
        return candidate
    }

    private fun isLoopback(address: String): Boolean {
        return runCatching {
            InetAddress.getByName(address.substringBefore('%')).isLoopbackAddress
        }.getOrDefault(false)
    }

    private fun dnsEndpoint(address: String): String {
        return if (':' in address) "[$address]:53" else "$address:53"
    }

    private val DEFAULT_ROUTE_DEVICE = Regex("(?:^|\\s)dev\\s+(\\S+)")
    private const val COMMAND_TIMEOUT_SECONDS = 2L
}
