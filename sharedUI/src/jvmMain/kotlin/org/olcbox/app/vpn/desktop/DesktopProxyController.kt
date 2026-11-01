package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.WindowsTrustedExecutables
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.util.Base64

internal interface DesktopProxyController {
    suspend fun recover() = Unit
    suspend fun enable(pacUrl: String)
    suspend fun restore()

    companion object {
        fun current(): DesktopProxyController {
            return when (DesktopPaths.os) {
                DesktopOs.Windows -> WindowsProxyController()
                DesktopOs.Linux -> UnsupportedProxyController()
                DesktopOs.Other -> UnsupportedProxyController()
            }
        }
    }
}

internal class UnsupportedProxyController : DesktopProxyController {
    override suspend fun enable(pacUrl: String) {
        error("System proxy mode is available on Windows only")
    }

    override suspend fun restore() = Unit
}

@Serializable
internal data class WindowsProxyState(
    val proxyEnable: String?,
    val proxyServer: String?,
    val proxyOverride: String?,
    val autoConfigUrl: String?
)

@Serializable
internal data class WindowsProxyLease(val pacUrl: String, val previous: WindowsProxyState)

internal class WindowsProxyController(
    private val regExecutable: String = WindowsTrustedExecutables.regPath().toString(),
    private val powershellExecutable: String = WindowsTrustedExecutables.powerShellPath().toString(),
    private val journal: Path = DesktopPaths.appDataDir().resolve("windows-proxy-lease.json"),
    private val execute: suspend (List<String>) -> String = ::runCommand
) : DesktopProxyController {
    private var lease: WindowsProxyLease? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    override suspend fun recover() {
        if (lease != null) return
        if (!acquire()) return // Another UnifiedVPN instance owns the proxy.
        try { recoverLocked() } finally { release() }
    }

    override suspend fun enable(pacUrl: String) {
        check(lease == null) { "System proxy is already enabled" }
        check(acquire()) { "Another UnifiedVPN instance owns the system proxy" }
        try {
            recoverLocked()
            val next = WindowsProxyLease(pacUrl, readState())
            // Persist before touching Windows so a new process can recover after a crash.
            DesktopPaths.writePrivateString(journal, Json.encodeToString(next))
            lease = next
            enableCommands(pacUrl, regExecutable).forEach { execute(it) }
            refreshProxySettings()
        } catch (error: Throwable) {
            try { recoverLocked() } catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            release()
            throw error
        }
    }

    override suspend fun restore() {
        if (lock == null && !acquire()) return
        try { recoverLocked() } finally { release() }
    }

    private fun acquire(): Boolean {
        if (lock != null) return true
        DesktopPaths.ensurePrivateDirectory(journal.toAbsolutePath().parent)
        val opened = FileChannel.open(journal.resolveSibling(journal.fileName.toString() + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired = try { opened.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        if (acquired == null) { opened.close(); return false }
        channel = opened
        lock = acquired
        return true
    }

    private fun release() {
        lock?.release()
        lock = null
        channel?.close()
        channel = null
    }

    private suspend fun recoverLocked() {
        val saved = lease ?: if (Files.exists(journal))
            Json.decodeFromString<WindowsProxyLease>(Files.readString(journal)) else return
        // Never overwrite proxy settings changed by the user or another application.
        if (queryValue("AutoConfigURL") == saved.pacUrl) {
            restoreCommands(saved.previous, regExecutable).forEach { execute(it) }
            refreshProxySettings()
        }
        Files.deleteIfExists(journal)
        lease = null
    }

    private suspend fun readState(): WindowsProxyState {
        return WindowsProxyState(
            proxyEnable = queryValue("ProxyEnable"),
            proxyServer = queryValue("ProxyServer"),
            proxyOverride = queryValue("ProxyOverride"),
            autoConfigUrl = queryValue("AutoConfigURL")
        )
    }

    private suspend fun queryValue(name: String): String? {
        val output = runCatching {
            execute(listOf(regExecutable, "query", REGISTRY_KEY, "/v", name))
        }.getOrNull() ?: return null

        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(name) }
            ?.split(Regex("\\s{2,}"))
            ?.lastOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun refreshProxySettings() {
        execute(refreshCommand(powershellExecutable))
    }

    companion object {
        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        fun enableCommands(pacUrl: String, regExecutable: String): List<List<String>> {
            return listOf(
                setStringCommand(regExecutable, "AutoConfigURL", pacUrl),
                setDwordCommand(regExecutable, "ProxyEnable", "0")
            )
        }

        fun restoreCommands(state: WindowsProxyState, regExecutable: String): List<List<String>> {
            return listOf(
                valueCommand(regExecutable, "ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand(regExecutable, "AutoConfigURL", state.autoConfigUrl, isDword = false)
            )
        }

        private fun valueCommand(
            regExecutable: String,
            name: String,
            value: String?,
            isDword: Boolean
        ): List<String> {
            return if (value == null) {
                listOf(regExecutable, "delete", REGISTRY_KEY, "/v", name, "/f")
            } else if (isDword) {
                setDwordCommand(
                    regExecutable,
                    name,
                    value.removePrefix("0x").toIntOrNull(16)?.toString() ?: value
                )
            } else {
                setStringCommand(regExecutable, name, value)
            }
        }

        private fun setStringCommand(
            regExecutable: String,
            name: String,
            value: String
        ): List<String> {
            return listOf(
                regExecutable,
                "add",
                REGISTRY_KEY,
                "/v",
                name,
                "/t",
                "REG_SZ",
                "/d",
                value,
                "/f"
            )
        }

        private fun setDwordCommand(
            regExecutable: String,
            name: String,
            value: String
        ): List<String> {
            return listOf(
                regExecutable,
                "add",
                REGISTRY_KEY,
                "/v",
                name,
                "/t",
                "REG_DWORD",
                "/d",
                value,
                "/f"
            )
        }

        fun refreshCommand(powershellExecutable: String): List<String> {
            val script = """
                ${'$'}signature = '[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);';
                Add-Type -MemberDefinition ${'$'}signature -Name WinInet -Namespace Native;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null;
            """.trimIndent()
            return listOf(
                powershellExecutable,
                "-NoProfile",
                "-NonInteractive",
                // Keep C# quotes intact across the Windows process argument
                // boundary; -Command loses the DllImport string's quotes.
                "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
            )
        }
    }
}

private suspend fun runCommand(command: List<String>): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("${command.joinToString(" ")} failed with code $exitCode: $output")
    }
    output
}
