package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false
    private var bypassRoutes = emptyList<BypassRoute>()

    suspend fun start(
        tun2SocksBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = "",
        bypassHosts: List<String> = emptyList()
    ): Process {
        ensureAdministratorOrRequestRestart()

        val process = ProcessBuilder(
            tun2SocksCommand(tun2SocksBinary, socksPort, socksUsername, socksPassword)
        )
            .directory(tun2SocksBinary.parent.toFile())
            .redirectErrorStream(true)
            .start()

        try {
            waitForAdapter(process)
            bypassRoutes = installRoutes(bypassHosts)
            routesInstalled = true
            addLog("Windows TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            runCatching { removeRoutes(bypassRoutes) }
                .onFailure { addLog("Windows TUN partial route cleanup failed: ${it.message}") }
            routesInstalled = false
            bypassRoutes = emptyList()
            stopProcess(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled) {
            runCatching { removeRoutes(bypassRoutes) }
                .onFailure { addLog("Windows TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
            bypassRoutes = emptyList()
        }

        stopProcess(process)
    }

    suspend fun ensureAdministratorOrRequestRestart() {
        if (isAdministrator()) return

        addLog("Requesting Windows administrator privileges for TUN mode")
        requestAdministratorRestart()
        exitProcess(0)
    }

    private suspend fun isAdministrator(): Boolean {
        val isAdmin = runPowerShell(
            """
            ${'$'}principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            if (${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }
            """.trimIndent()
        ).trim().equals("true", ignoreCase = true)

        return isAdmin
    }

    private suspend fun requestAdministratorRestart() {
        val processInfo = windowsLauncherProcess().info()
        val currentCommand = processInfo.command().orElse(null)
            ?: error("Olcbox cannot resolve its Windows launcher for administrator restart")
        val currentArguments = processInfo.arguments().orElse(emptyArray()).toList()
        val restartArguments = if (ELEVATED_START_ARGUMENT in currentArguments) {
            currentArguments
        } else {
            currentArguments + ELEVATED_START_ARGUMENT
        }

        val elevatedPid = runPowerShell(
            restartAsAdministratorScript(
                command = currentCommand,
                arguments = restartArguments,
                workingDirectory = System.getProperty("user.dir").orEmpty()
            )
        ).lineSequence()
            .map(String::trim)
            .lastOrNull { it.toLongOrNull() != null }
            ?.toLongOrNull()
            ?: error("Windows did not return the elevated Unified VPN process id")

        delay(ELEVATED_PROCESS_START_WAIT_MS)
        check(ProcessHandle.of(elevatedPid).orElse(null)?.isAlive == true) {
            "Elevated Unified VPN exited before startup; use System proxy or run the app as administrator"
        }
    }

    private fun windowsLauncherProcess(): ProcessHandle {
        val processChain = generateSequence(ProcessHandle.current()) { handle ->
            handle.parent().orElse(null)
        }.toList()
        return processChain.lastOrNull { handle ->
            handle.info().command().orElse("")
                .substringAfterLast('\\')
                .equals("UnifiedVPN.exe", ignoreCase = true)
        } ?: ProcessHandle.current()
    }

    private suspend fun waitForAdapter(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("tun2socks exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }

            if (adapterExists()) return
            delay(TUN_READY_POLL_MS)
        }

        error("$TUN_NAME adapter was not created")
    }

    private suspend fun adapterExists(): Boolean {
        return runCatching {
            runPowerShell(
                """
                ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
                if (${'$'}null -ne ${'$'}adapter) { 'true' } else { 'false' }
                """.trimIndent()
            ).trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    private suspend fun installRoutes(bypassHosts: List<String>): List<BypassRoute> {
        val hostArray = bypassHosts
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(separator = ", ") { it.powershellLiteral() }
            .ifBlank { "@()" }
            .let { if (it == "@()") it else "@($it)" }
        val output = runPowerShell(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction Stop
            ${'$'}ifIndex = ${'$'}adapter.ifIndex
            ${'$'}physicalRoute = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
              Where-Object { ${'$'}_.InterfaceIndex -ne ${'$'}ifIndex -and ${'$'}_.NextHop -ne '0.0.0.0' } |
              Sort-Object RouteMetric, InterfaceMetric |
              Select-Object -First 1
            if (${'$'}null -eq ${'$'}physicalRoute) { throw 'No physical IPv4 default route was found' }

            ${'$'}bypassAddresses = foreach (${'$'}hostName in $hostArray) {
              try {
                [System.Net.Dns]::GetHostAddresses(${'$'}hostName) |
                  Where-Object { ${'$'}_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
                  ForEach-Object { ${'$'}_.IPAddressToString }
              } catch {
                throw "Could not resolve VPN server ${'$'}hostName"
              }
            }
            foreach (${'$'}address in (${'$'}bypassAddresses | Select-Object -Unique)) {
              ${'$'}prefix = "${'$'}address/32"
              ${'$'}existing = Get-NetRoute -InterfaceIndex ${'$'}physicalRoute.InterfaceIndex -DestinationPrefix ${'$'}prefix -ErrorAction SilentlyContinue |
                Where-Object { ${'$'}_.NextHop -eq ${'$'}physicalRoute.NextHop } |
                Select-Object -First 1
              if (${'$'}null -eq ${'$'}existing) {
                New-NetRoute -InterfaceIndex ${'$'}physicalRoute.InterfaceIndex -DestinationPrefix ${'$'}prefix -NextHop ${'$'}physicalRoute.NextHop -RouteMetric $BYPASS_ROUTE_METRIC | Out-Null
                "OLCBOX_BYPASS|${'$'}prefix|${'$'}(${'$'}physicalRoute.InterfaceIndex)|${'$'}(${'$'}physicalRoute.NextHop)"
              }
            }

            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetIPAddress -InterfaceIndex ${'$'}ifIndex -IPAddress '$TUN_IPV4_ADDRESS' -PrefixLength $TUN_IPV4_PREFIX_LENGTH -AddressFamily IPv4 | Out-Null

            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ServerAddresses '$MAPDNS_ADDRESS'
            """.trimIndent()
        )
        return output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(BYPASS_OUTPUT_PREFIX) }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 4) return@mapNotNull null
                val interfaceIndex = parts[2].toIntOrNull() ?: return@mapNotNull null
                BypassRoute(parts[1], interfaceIndex, parts[3])
            }
            .toList()
    }

    private suspend fun removeRoutes(bypassRoutes: List<BypassRoute>) {
        val removeBypassScript = bypassRoutes.joinToString(separator = "\n") { route ->
            """
            Get-NetRoute -InterfaceIndex ${route.interfaceIndex} -DestinationPrefix ${route.prefix.powershellLiteral()} -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.NextHop -eq ${route.nextHop.powershellLiteral()} } |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            """.trimIndent()
        }
        runPowerShell(
            """
            $removeBypassScript
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
            if (${'$'}null -eq ${'$'}adapter) { exit 0 }
            ${'$'}ifIndex = ${'$'}adapter.ifIndex
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue
            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue
            """.trimIndent()
        )
    }

    private data class BypassRoute(
        val prefix: String,
        val interfaceIndex: Int,
        val nextHop: String
    )

    private suspend fun runPowerShell(script: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("PowerShell failed with code $exitCode: $output")
        }
        output
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "Olcbox"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val TUN_IPV4_PREFIX_LENGTH = 24
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val BYPASS_ROUTE_METRIC = 3
        const val BYPASS_OUTPUT_PREFIX = "OLCBOX_BYPASS|"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val ELEVATED_PROCESS_START_WAIT_MS = 1_500L
        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

        fun tun2SocksCommand(
            tun2SocksBinary: Path,
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
            socksUsername: String = "",
            socksPassword: String = ""
        ): List<String> = listOf(
            tun2SocksBinary.toString(),
            "--device",
            TUN_NAME,
            "--proxy",
            "socks5://${PacServer.socksProxyUri(PacServer.LOCAL_SOCKS_HOST, socksPort, socksUsername, socksPassword)}",
            "--mtu",
            TUN_MTU.toString(),
            "--loglevel",
            "warn"
        )

        fun restartAsAdministratorScript(
            command: String,
            arguments: List<String>,
            workingDirectory: String
        ): String {
            val quotedArguments = arguments
                .joinToString(separator = " ") { it.windowsCommandLineArgument() }
                .powershellLiteral()
            val workingDirectoryLine = workingDirectory
                .takeIf { it.isNotBlank() }
                ?.let { "  WorkingDirectory = ${it.powershellLiteral()}" }
                .orEmpty()

            return """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}startArgs = @{
                  FilePath = ${command.powershellLiteral()}
                  Verb = 'RunAs'
                  ArgumentList = $quotedArguments
                $workingDirectoryLine
                }
                ${'$'}process = Start-Process @startArgs -PassThru
                ${'$'}process.Id
            """.trimIndent()
        }

        private fun String.powershellLiteral(): String = "'${replace("'", "''")}'"

        private fun String.windowsCommandLineArgument(): String {
            if (isEmpty()) return "\"\""
            if (none { it.isWhitespace() || it == '"' }) return this

            val quoted = StringBuilder("\"")
            var pendingBackslashes = 0
            for (char in this) {
                when (char) {
                    '\\' -> pendingBackslashes++
                    '"' -> {
                        repeat(pendingBackslashes * 2 + 1) { quoted.append('\\') }
                        quoted.append(char)
                        pendingBackslashes = 0
                    }
                    else -> {
                        repeat(pendingBackslashes) { quoted.append('\\') }
                        pendingBackslashes = 0
                        quoted.append(char)
                    }
                }
            }
            repeat(pendingBackslashes * 2) { quoted.append('\\') }
            return quoted.append('"').toString()
        }
    }
}
