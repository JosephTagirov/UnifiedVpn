package org.olcbox.app.vpn.desktop

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.ClientDirectRoutingConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.XraySocksSettings

/** One authenticated AWG child behind a stable Xray front; it owns both native lifetimes and configs. */
internal class DesktopRoutedAwgProcess(
    private val processStarter: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
    private val nativeBinary: (String) -> Path = { source ->
        if (source == "awg") DesktopNativeAssets.resolveSingBoxBinary() else DesktopNativeAssets.resolveXrayBinary()
    }
) : AutoCloseable {
    private val cleanupLock = Any()
    @Volatile private var closed = false
    private var started = false
    @Volatile var front: Process? = null
        private set
    @Volatile var child: Process? = null
        private set
    private val processes = mutableListOf<Process>()
    private val configPaths = mutableListOf<Path>()
    private val logThreads = mutableListOf<Thread>()

    override fun close() {
        closed = true
        synchronized(cleanupLock) {
            var failure: Throwable? = null
            for (process in processes.asReversed()) try { stop(process) } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            logThreads.forEach { it.interrupt() }
            if (failure == null) {
                configPaths.forEach { Files.deleteIfExists(it) }
                configPaths.clear(); processes.clear(); logThreads.clear(); front = null; child = null
            }
            failure?.let { throw it }
        }
    }

    suspend fun start(
        profile: VpnProfileConfig,
        socks: DesktopSocksProxySettings,
        endpointAddress: String?,
        physicalInterface: String,
        shouldContinue: () -> Boolean,
        log: (source: String, line: String) -> Unit,
        excludedPorts: Set<Int> = emptySet(),
        runtimeDirectory: Path = DesktopPaths.appDataDir().resolve("runtime")
    ): Process {
        synchronized(cleanupLock) {
            checkCurrent { !closed && shouldContinue() }
            check(!started) { "Routed AWG process owners can only start once" }
            started = true
        }
        val settings = socks.normalized()
        val childPort = LocalPortAllocator.select("127.0.0.1",
            if (settings.port < DesktopSocksProxySettings.MAX_PORT) settings.port + 1 else DesktopSocksProxySettings.MIN_PORT,
            excludedPorts = excludedPorts + setOf(settings.port, PacServer.PAC_PORT))
        val childSettings = settings.copy(host = "127.0.0.1", port = childPort,
            username = UUID.randomUUID().toString().replace("-", ""),
            password = UUID.randomUUID().toString().replace("-", ""), blockAds = false)
        try {
            val startupContext = currentCoroutineContext()
            val isCurrent = { !closed && shouldContinue() }
            fun launch(source: String, arguments: List<String>, config: String): Process = synchronized(cleanupLock) {
                startupContext.ensureActive()
                checkCurrent(isCurrent)
                val binary = nativeBinary(source)
                startupContext.ensureActive()
                checkCurrent(isCurrent)
                val path = DesktopPaths.writePrivateTempString(runtimeDirectory, "routed-$source-", ".json", config)
                configPaths.add(path)
                val process = processStarter(listOf(binary.toAbsolutePath().toString()) + arguments + path.toAbsolutePath().toString())
                processes += process
                if (source == "awg") child = process else front = process
                logThreads += thread(name = "DesktopRouted-$source", isDaemon = true) {
                    try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { log(source, it) } } }
                    catch (_: IOException) { /* Owned process shutdown closes the pipe. */ }
                }
                process
            }
            val child = launch("awg", listOf("run", "-c"),
                DesktopSingBoxConfig.build(profile, childSettings, endpointAddress))
            awaitReady(child, childSettings.port, isCurrent)
            val frontConfig = ClientDirectRoutingConfig.buildSocksFront(
                XraySocksSettings(settings.host, settings.port, settings.username, settings.password),
                XraySocksSettings(childSettings.host, childSettings.port, childSettings.username, childSettings.password),
                physicalInterface, profile)
            val front = launch("xray", listOf("run", "-config"), frontConfig)
            awaitReady(front, settings.port, isCurrent)
            check(child.isAlive) { "AWG child exited before the routing front became ready" }
            currentCoroutineContext().ensureActive()
            checkCurrent(isCurrent)
            return front
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    companion object {
        private fun checkCurrent(shouldContinue: () -> Boolean) {
            if (!shouldContinue()) throw CancellationException("Desktop routing start superseded")
        }

        private suspend fun awaitReady(process: Process, port: Int, shouldContinue: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                checkCurrent(shouldContinue)
                check(process.isAlive) { "Native routing process exited before readiness" }
                if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return
                delay(50)
            }
            error("Native routing listener did not become ready")
        }

        private fun stop(process: Process) {
            try {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        check(process.waitFor(2, TimeUnit.SECONDS)) { "Native routing process did not stop" }
                    }
                }
                check(!process.isAlive) { "Native routing process is still alive" }
            } finally {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }
    }
}
