package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CancellationException
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

    suspend fun start(
        front: DesktopSocksProxySettings,
        upstream: DesktopSocksProxySettings,
        preserveUpstreamDns: Boolean
    ): Boolean {
        check(process == null)
        val directory = DesktopPaths.appDataDir().resolve("adblocking").toFile()
        val path = directory.toPath().resolve("proxy-${front.port}.json")

        fun config(rulesPath: String?): String = AdBlockingConfig.build(
            front.host, front.port, front.username, front.password, upstream.host, upstream.port,
            rulesPath, upstream.username, upstream.password, preserveUpstreamDns
        )
        try {
            val rules = AdBlockingRules.install(directory)
            startProxy(path.toFile(), config(rules.absolutePath), front, 20_000)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            startProxy(path.toFile(), config(null), front, 8_000)
            return false
        }
    }

    private suspend fun startProxy(path: File, content: String, front: DesktopSocksProxySettings, timeoutMs: Long) {
        config = path
        try {
            DesktopPaths.writePrivateString(path.toPath(), content)
            val started = ProcessBuilder(DesktopNativeAssets.resolveSingBoxBinary().toString(),
                "run", "-c", path.absolutePath).redirectErrorStream(true).start()
            process = started
            // Drain without recording browsing domains or credentials in the application log.
            logThread = thread(name = "AdBlockingLog", isDaemon = true) {
                runCatching { started.inputStream.bufferedReader().useLines { lines -> lines.forEach { } } }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                check(started.isAlive) { "Ad blocking bridge exited before becoming ready (code ${started.exitValue()})" }
                if (runCatching { Socket(Proxy.NO_PROXY).use {
                    it.connect(InetSocketAddress(AdBlockingConfig.localProbeHost(front.host), front.port), 150)
                } }.isSuccess) return
                delay(100)
            }
            error("Ad blocking bridge did not become ready")
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
