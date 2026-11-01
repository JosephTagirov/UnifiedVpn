package org.olcbox.app.vpn.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.VkCallsRuntimeConfig
import org.olcbox.app.vpn.androidRtcDnsEndpoint
import org.olcbox.app.vpn.retryOlcRtcStartup
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal fun createVkCallsVpnEngine(
    context: Context, location: LocationConfig, port: Int,
    user: String, password: String, log: (String) -> Unit
): SocksBackedVpnEngine = VkCallsVpnEngine(location, port, user, password, log,
    dnsServer = androidRtcDnsEndpoint(context, location.dnsServer), prepareRuntime = {
    val label = location.providerName()
    val executable = File(context.applicationInfo.nativeLibraryDir, "libvkcalls.so")
    check(executable.isFile && executable.canExecute()) { "$label executable is missing for this Android ABI" }
    val directory = File(context.noBackupFilesDir, "engines/vkcalls")
    check(directory.isDirectory || directory.mkdirs()) { "$label working directory is unavailable" }
    val config = File.createTempFile("client-", ".json", directory)
    try {
        check(config.setReadable(false, false) && config.setWritable(false, false) &&
            config.setReadable(true, true) && config.setWritable(true, true)) {
            "$label configuration permissions could not be set"
        }
        VkCallsRuntimeFiles(executable, directory, config)
    } catch (error: Throwable) {
        config.delete()
        throw error
    }
})

internal data class VkCallsRuntimeFiles(val executable: File, val directory: File, val config: File)

internal class VkCallsVpnEngine(
    private val location: LocationConfig,
    override val socksPort: Int,
    private val user: String,
    private val password: String,
    private val log: (String) -> Unit,
    private val prepareRuntime: () -> VkCallsRuntimeFiles,
    private val launchProcess: (List<String>, File) -> Process = { args, directory ->
        ProcessBuilder(args).directory(directory).redirectErrorStream(true).apply {
            listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")
                .forEach { environment().remove(it) }
        }.start()
    },
    private val readyProbe: () -> Boolean = { VkCallsRuntimeConfig.probeSocks(socksPort, user, password) },
    private val outputReader: (Process, (String) -> Unit) -> Thread? = { process, consume ->
        thread(name = "VkCallsLog", isDaemon = true) {
            try { consumeOpenFluxEngineOutput(process.inputStream, consume) }
            catch (_: IOException) { /* Closing stdout is part of bounded process shutdown. */ }
        }
    },
    private val readyTimeoutMillis: Long = 90_000,
    private val dnsServer: String = location.dnsServer.ifBlank { "1.1.1.1:53" }
) : SocksBackedVpnEngine {
    override val profileType = VkCallsRuntimeConfig.engineType(location)
    private val label = location.providerName()
    override val socksHost = "127.0.0.1"
    private val lock = Any()
    private val generation = AtomicLong()
    private var running: Run? = null
    override val isRunning: Boolean
        get() = synchronized(lock) { running?.let { !it.stopping.get() && it.ready.get() && it.process.isAlive } == true }

    override suspend fun start() {
        stop()
        val token = generation.incrementAndGet()
        log("$label: using RTC signaling DNS $dnsServer")
        val config = VkCallsRuntimeConfig.build(location, socksPort, user, password, dnsServer)
        retryOlcRtcStartup(
            isCurrent = { generation.get() == token },
            onRetry = { retry, _ -> log("$label temporary start failure; retry $retry/3") }
        ) { startAttempt(token, config) }
    }

    private suspend fun startAttempt(token: Long, config: String) {
        val context = currentCoroutineContext()
        val started = synchronized(lock) {
            context.ensureActive()
            if (generation.get() != token) throw CancellationException("VK Calls start was stopped")
            val files = prepareRuntime()
            try {
                files.config.writeText(config)
                context.ensureActive()
                Run(launchProcess(listOf(files.executable.absolutePath, files.config.absolutePath), files.directory), files.config)
                    .also { running = it }
            } catch (error: Throwable) {
                files.config.delete()
                throw error
            }
        }
        try {
            started.reader = outputReader(started.process) { line ->
                if (!started.stopping.get()) {
                    if (line.contains(VkCallsRuntimeConfig.READY_MARKER)) started.ready.set(true)
                    val safe = VkCallsRuntimeConfig.sanitizedOutput(line, location, user, password)
                    if (line.contains("unknown authority", ignoreCase = true) ||
                        line.contains("validate config", ignoreCase = true)) started.failure = safe
                    log("$label: " + safe)
                }
            }
            val ready = withTimeoutOrNull(readyTimeoutMillis) {
                while (true) {
                    context.ensureActive()
                    checkCurrent(started, token)
                    check(started.process.isAlive) { started.failure ?: "olcRTC exited before SOCKS5 was ready" }
                    if (started.ready.get() && readyProbe()) return@withTimeoutOrNull true
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
            check(ready) { "olcRTC start timed out" }
            checkCurrent(started, token)
        } catch (error: Throwable) {
            runCatching { stopRun(started) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun checkCurrent(started: Run, token: Long) = synchronized(lock) {
        if (running !== started || started.stopping.get() || generation.get() != token) {
            throw CancellationException("VK Calls start superseded")
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        synchronized(lock) { running }?.let(::stopRun)
    }

    private fun stopRun(started: Run) {
        synchronized(started) {
            started.stopping.set(true)
            started.process.destroy()
            if (!started.process.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                started.process.destroyForcibly()
                check(started.process.waitFor(1_000, TimeUnit.MILLISECONDS)) { "VK Calls process is still stopping" }
            }
            started.reader?.interrupt()
            runCatching { started.process.inputStream.close() }
            check(started.config.delete() || !started.config.exists()) { "VK Calls temporary configuration cleanup failed" }
            synchronized(lock) { if (running === started) running = null }
        }
    }

    private class Run(val process: Process, val config: File) {
        val ready = AtomicBoolean()
        val stopping = AtomicBoolean()
        @Volatile var reader: Thread? = null
        @Volatile var failure: String? = null
    }
}

