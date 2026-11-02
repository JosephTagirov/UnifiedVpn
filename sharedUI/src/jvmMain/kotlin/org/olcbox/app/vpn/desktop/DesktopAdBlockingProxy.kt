package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.delay
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.AdBlockingConfig
import org.olcbox.app.vpn.AdBlockingRules
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class DesktopAdBlockingProxy {
    var process: Process? = null
        private set
    private var config: File? = null
    private var logThread: Thread? = null

    suspend fun start(front: DesktopSocksProxySettings, upstream: DesktopSocksProxySettings, preserveUpstreamDns: Boolean) {
        check(process == null)
        val directory = DesktopPaths.appDataDir().resolve("adblocking").toFile()
        val rules = AdBlockingRules.install(directory)
        val path = directory.toPath().resolve("proxy-${front.port}.json")
        DesktopPaths.writePrivateString(path, AdBlockingConfig.build(
            front.host, front.port, front.username, front.password,
            upstream.host, upstream.port, rules.absolutePath, upstream.username, upstream.password, preserveUpstreamDns
        ))
        config = path.toFile()
        try {
            val started = ProcessBuilder(DesktopNativeAssets.resolveSingBoxBinary().toString(),
                "run", "-c", path.toString()).redirectErrorStream(true).start()
            process = started
            // Drain without recording browsing domains or credentials in the application log.
            logThread = thread(name = "AdBlockingLog", isDaemon = true) {
                runCatching { started.inputStream.bufferedReader().useLines { lines -> lines.forEach { } } }
            }
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                check(started.isAlive) { "Ad blocking filter exited before becoming ready" }
                if (runCatching { Socket(Proxy.NO_PROXY).use {
                    it.connect(InetSocketAddress("127.0.0.1", front.port), 150)
                } }.isSuccess) return
                delay(100)
            }
            error("Ad blocking filter did not become ready")
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun close() {
        val running = process
        process = null
        running?.destroy()
        if (running != null && !running.waitFor(1500, TimeUnit.MILLISECONDS)) {
            running.destroyForcibly(); running.waitFor(1000, TimeUnit.MILLISECONDS)
        }
        logThread?.interrupt(); logThread = null
        config?.delete(); config = null
    }
}
