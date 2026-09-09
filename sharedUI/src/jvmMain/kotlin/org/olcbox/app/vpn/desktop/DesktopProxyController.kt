package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.WindowsTrustedExecutables

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

internal class WindowsProxyController(
    private val regExecutable: String = WindowsTrustedExecutables.regPath().toString(),
    private val powershellExecutable: String = WindowsTrustedExecutables.powerShellPath().toString()
) : DesktopProxyController {
    private var backup: WindowsProxyState? = null

    override suspend fun enable(pacUrl: String) {
        backup = readState()
        enableCommands(pacUrl, regExecutable).forEach { runCommand(it) }
        refreshProxySettings()
    }

    override suspend fun restore() {
        val state = backup ?: return
        restoreCommands(state, regExecutable).forEach { command ->
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
            runCommand(listOf(regExecutable, "query", REGISTRY_KEY, "/v", name))
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
        runCatching { runCommand(refreshCommand(powershellExecutable)) }
    }

    companion object {
        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        fun enableCommands(pacUrl: String, regExecutable: String): List<List<String>> {
            return listOf(
                setDwordCommand(regExecutable, "ProxyEnable", "0"),
                setStringCommand(regExecutable, "AutoConfigURL", pacUrl)
            )
        }

        fun restoreCommands(state: WindowsProxyState, regExecutable: String): List<List<String>> {
            return listOf(
                valueCommand(regExecutable, "ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand(regExecutable, "ProxyServer", state.proxyServer, isDword = false),
                valueCommand(regExecutable, "ProxyOverride", state.proxyOverride, isDword = false),
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
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script
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
