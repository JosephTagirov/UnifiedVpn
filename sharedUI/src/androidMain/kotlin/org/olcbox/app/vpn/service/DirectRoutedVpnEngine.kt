package org.olcbox.app.vpn.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.XraySocksSettings
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A single VPN backend behind a local policy router. The service binds both processes to its physical network. */
internal class DirectRoutedVpnEngine(
    private val context: Context,
    private val profile: VpnProfileConfig,
    override val socksPort: Int,
    username: String,
    password: String,
    private val log: (String) -> Unit,
    backendFactory: (Int, String, String, (String) -> Unit) -> SocksBackedVpnEngine
) : SocksBackedVpnEngine {
    override val profileType = profile.normalizedType
    override val socksHost = "127.0.0.1"
    override val usesClientDirectRouting = true
    override val isRunning: Boolean
        get() = !stopped && frontProcess?.isAlive == true && backend.isRunning && canConnect(socksHost, socksPort)

    private val mainSocks = XraySocksSettings(socksHost, socksPort, username, password)
    private val backendSocks = XraySocksSettings(port = privatePort(), username = credential(), password = credential())
    // Retain the object across stop, including startup races, so every retry can stop a late-created process.
    private val backend = backendFactory(backendSocks.port, backendSocks.username, backendSocks.password) {
        log("${profile.typeLabel()} backend: $it")
    }
    private val epoch = AtomicLong()
    private val resources = Any()
    private val cleanupLock = Any()
    @Volatile private var stopped = true
    @Volatile private var frontProcess: Process? = null
    private var runtimeDir: File? = null
    private var logThread: Thread? = null

    override suspend fun start() = withContext(Dispatchers.IO) {
        stop()
        val token = epoch.incrementAndGet()
        stopped = false
        try {
            backend.start()
            currentCoroutineContext().ensureActive()
            checkCurrent(token)
            startFront(token)
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (SystemClock.elapsedRealtime() < deadline) {
                checkCurrent(token)
                check(frontProcess?.isAlive == true) { "Local direct-routing core exited before readiness" }
                if (canConnect(socksHost, socksPort)) {
                    log("${profile.typeLabel()} local direct routing ready")
                    return@withContext
                }
                delay(100)
            }
            error("Local direct-routing SOCKS did not become ready")
        } catch (failure: Exception) {
            try { stop() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun stop() {
        stopped = true
        epoch.incrementAndGet()
        synchronized(cleanupLock) {
            var cleanupFailure: Exception? = null
            synchronized(resources) {
                frontProcess?.let { process ->
                    try {
                        process.destroy()
                        if (!process.waitFor(1_000, TimeUnit.MILLISECONDS)) {
                            process.destroyForcibly()
                            process.waitFor(1_000, TimeUnit.MILLISECONDS)
                        }
                        check(!process.isAlive) { "Local direct-routing core has not stopped" }
                        frontProcess = null
                    } catch (failure: Exception) {
                        process.destroyForcibly()
                        cleanupFailure = failure
                    }
                }
            }
            try { backend.stop() } catch (failure: Exception) { cleanupFailure = cleanupFailure ?: failure }
            logThread?.interrupt()
            logThread = null
            if (cleanupFailure != null) throw IllegalStateException("Local direct-routing cleanup is pending", cleanupFailure)
            runtimeDir?.let { dir -> dir.listFiles()?.forEach { it.delete() }; dir.delete() }
            runtimeDir = null
        }
    }

    private fun startFront(token: Long) {
        val source = findEngineExecutable(context, "xray") ?: error("The packaged Xray core is missing")
        val binary = source.file ?: File(context.noBackupFilesDir, "engines/xray/${source.abi}/xray").also {
            it.parentFile?.mkdirs()
            copyAsset(context, source.assetPath.orEmpty(), it)
            it.setExecutable(true, true)
        }
        val dir = File(context.noBackupFilesDir, "dr/${UUID.randomUUID().toString().take(12)}").apply { mkdirs() }
        dir.setReadable(false, false); dir.setReadable(true, true)
        dir.setWritable(false, false); dir.setWritable(true, true)
        dir.setExecutable(false, false); dir.setExecutable(true, true)
        val config = File(dir, "f.json")
        synchronized(resources) {
            try {
                checkCurrent(token)
                runtimeDir = dir
                config.writeText(buildAndroidDirectSocksConfig(mainSocks, backendSocks, profile))
                config.setReadable(false, false); config.setReadable(true, true)
                config.setWritable(false, false); config.setWritable(true, true)
                frontProcess = ProcessBuilder(binary.absolutePath, "run", "-config", config.absolutePath)
                    .directory(dir).redirectErrorStream(true).start()
                logThread = startEngineLogReader("DirectRoutingXrayLog", "direct-router", checkNotNull(frontProcess), log)
            } catch (failure: Exception) {
                config.delete(); dir.delete()
                throw failure
            }
        }
    }

    private fun checkCurrent(token: Long) {
        if (stopped || token != epoch.get()) throw CancellationException("Local direct-routing session stopped")
    }

    private fun privatePort(): Int {
        var port: Int
        do { port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } } while (port == socksPort)
        return port
    }
    private fun credential() = UUID.randomUUID().toString().replace("-", "")
}
