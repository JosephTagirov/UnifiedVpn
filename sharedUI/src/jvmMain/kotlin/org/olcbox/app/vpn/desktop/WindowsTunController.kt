package org.olcbox.app.vpn.desktop

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    suspend fun start(
        @Suppress("UNUSED_PARAMETER") tun2SocksBinary: Path,
        @Suppress("UNUSED_PARAMETER") socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        @Suppress("UNUSED_PARAMETER") socksUsername: String = "",
        @Suppress("UNUSED_PARAMETER") socksPassword: String = "",
        @Suppress("UNUSED_PARAMETER") bypassHosts: List<String> = emptyList()
    ): Process = ensureAdministratorOrRequestRestart()

    suspend fun stop(process: Process?) {
        stopProcess(process)
    }

    fun ensureAdministratorOrRequestRestart(): Nothing {
        val message =
            "Windows TUN is temporarily unavailable until Unified VPN has a narrow protected " +
                "network helper. Use System proxy or Local SOCKS mode."
        addLog(message)
        error(message)
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
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

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
    }
}
