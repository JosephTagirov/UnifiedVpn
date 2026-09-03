package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

internal interface DesktopProxyController {
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

internal data class WindowsProxyState(
    val proxyEnable: String?,
    val proxyServer: String?,
    val proxyOverride: String?,
    val autoConfigUrl: String?
)

internal class WindowsProxyController : DesktopProxyController {
    private var backup: WindowsProxyState? = null

    override suspend fun enable(pacUrl: String) {
        backup = readState()
        enableCommands(pacUrl).forEach { runCommand(it) }
        refreshProxySettings()
    }

    override suspend fun restore() {
        val state = backup ?: return
        restoreCommands(state).forEach { command ->
            runCatching { runCommand(command) }
        }
        refreshProxySettings()
        backup = null
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
            runCommand(listOf("reg", "query", REGISTRY_KEY, "/v", name))
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
        runCatching { runCommand(refreshCommand()) }
    }

    companion object {
        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        fun enableCommands(pacUrl: String): List<List<String>> {
            return listOf(
                setDwordCommand("ProxyEnable", "0"),
                setStringCommand("AutoConfigURL", pacUrl)
            )
        }

        fun restoreCommands(state: WindowsProxyState): List<List<String>> {
            return listOf(
                valueCommand("ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand("ProxyServer", state.proxyServer, isDword = false),
                valueCommand("ProxyOverride", state.proxyOverride, isDword = false),
                valueCommand("AutoConfigURL", state.autoConfigUrl, isDword = false)
            )
        }

        private fun valueCommand(name: String, value: String?, isDword: Boolean): List<String> {
            return if (value == null) {
                listOf("reg", "delete", REGISTRY_KEY, "/v", name, "/f")
            } else if (isDword) {
                setDwordCommand(name, value.removePrefix("0x").toIntOrNull(16)?.toString() ?: value)
            } else {
                setStringCommand(name, value)
            }
        }

        private fun setStringCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_SZ", "/d", value, "/f")
        }

        private fun setDwordCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_DWORD", "/d", value, "/f")
        }

        fun refreshCommand(): List<String> {
            val script = """
                ${'$'}signature = '[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);';
                Add-Type -MemberDefinition ${'$'}signature -Name WinInet -Namespace Native;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null;
            """.trimIndent()
            return listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
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
