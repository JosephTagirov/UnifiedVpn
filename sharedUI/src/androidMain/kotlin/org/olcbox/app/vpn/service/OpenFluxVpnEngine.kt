package org.olcbox.app.vpn.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.logging.sanitizeDiagnosticLogLine
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal fun createOpenFluxVpnEngine(
    context: Context,
    profile: VpnProfileConfig,
    socksPort: Int,
    username: String,
    password: String,
    log: (String) -> Unit
): SocksBackedVpnEngine = OpenFluxVpnEngine(
    profile = profile,
    socksPort = socksPort,
    username = username,
    password = password,
    log = log,
    prepareRuntime = {
        val nativeDirectory = context.applicationInfo.nativeLibraryDir?.let(::File)
        val executable = nativeDirectory?.resolve("libopenflux.so")
            ?: throw IllegalStateException("OpenFlux native library directory is unavailable")
        check(executable.isFile && executable.canExecute()) {
            "OpenFlux executable is missing for this Android ABI"
        }
        val workDirectory = File(context.noBackupFilesDir, "engines/openflux")
        check(workDirectory.isDirectory || workDirectory.mkdirs()) {
            "OpenFlux private working directory is unavailable"
        }
        OpenFluxRuntimeFiles(executable, workDirectory, createPrivateOpenFluxConfig(workDirectory))
    }
)

internal data class OpenFluxRuntimeFiles(
    val executable: File,
    val workDirectory: File,
    val configFile: File
)

private fun createPrivateOpenFluxConfig(directory: File): File {
    val configFile = File.createTempFile("client-", ".json", directory)
    try {
        check(
            configFile.setReadable(false, false) &&
                configFile.setWritable(false, false) &&
                configFile.setExecutable(false, false) &&
                configFile.setReadable(true, true) &&
                configFile.setWritable(true, true)
        ) {
            "OpenFlux private configuration permissions could not be set"
        }
        return configFile
    } catch (failure: Throwable) {
        configFile.delete()
        throw failure
    }
}

internal class OpenFluxVpnEngine(
    private val profile: VpnProfileConfig,
    override val socksPort: Int,
    private val username: String,
    private val password: String,
    private val log: (String) -> Unit,
    private val prepareRuntime: () -> OpenFluxRuntimeFiles,
    private val startProcess: (List<String>, File) -> Process = { command, directory ->
        ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
    },
    private val socksReadyProbe: () -> Boolean = {
        probeOpenFluxSocksListener(socksPort, username, password)
    },
    private val startOutputReader: (Process, (String) -> Unit) -> Thread? =
        ::startOpenFluxOutputReader
) : SocksBackedVpnEngine {
    override val profileType: String = VpnProfileConfig.TYPE_OPENFLUX
    override val socksHost: String = OPENFLUX_SOCKS_HOST
    override val isRunning: Boolean
        get() {
            val started = synchronized(lifecycleLock) { running } ?: return false
            if (started.stopping.get() || !started.process.isAlive || !started.ready.get()) return false
            if (!socksReadyProbe()) return false
            return synchronized(lifecycleLock) {
                running === started && !started.stopping.get() && started.process.isAlive
            }
        }

    private val lifecycleLock = Any()
    private var running: RunningOpenFlux? = null

    override suspend fun start() {
        stop()
        require(socksPort in 1..65535) { "Invalid OpenFlux SOCKS port" }
        val rawConfig = profile.rawConfig?.takeIf(String::isNotBlank) ?: profile.uri.orEmpty()
        val config = OpenFluxProfileConfig.parse(rawConfig)?.normalized()
            ?.takeIf(OpenFluxProfileConfig::isValid)
            ?: throw IllegalArgumentException("OpenFlux profile is invalid")
        val coroutineContext = currentCoroutineContext()
        val started = synchronized(lifecycleLock) {
            coroutineContext.ensureActive()
            val files = prepareRuntime()
            val configFile = files.configFile
            try {
                configFile.writeText(
                    config.toEngineJson(
                        mode = "client",
                        socksHost = socksHost,
                        socksPort = socksPort,
                        socksUsername = username,
                        socksPassword = password
                    )
                )
                coroutineContext.ensureActive()
                RunningOpenFlux(
                    process = startProcess(
                        listOf(files.executable.absolutePath, "--config", configFile.absolutePath),
                        files.workDirectory
                    ),
                    configFile = configFile
                ).also { running = it }
            } catch (failure: Throwable) {
                configFile.delete()
                throw failure
            }
        }

        try {
            started.outputThread = startOutputReader(started.process) { line ->
                if (line == OPENFLUX_READY_MARKER && !started.stopping.get()) {
                    started.ready.set(true)
                } else {
                    log(sanitizeOpenFluxEngineOutput(line, config, username, password))
                }
            }
            val ready = withTimeoutOrNull(OPENFLUX_READY_TIMEOUT_MS) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    requireActiveRun(started)
                    if (started.ready.get() && socksReadyProbe()) {
                        currentCoroutineContext().ensureActive()
                        requireActiveRun(started)
                        return@withTimeoutOrNull true
                    }
                    delay(OPENFLUX_READY_POLL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false
            check(ready) { "OpenFlux encrypted transport did not become ready within 90 seconds" }
            log("OpenFlux encrypted transport ready on $socksHost:$socksPort")
        } catch (failure: Throwable) {
            runCatching { stopRun(started) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun stop() {
        val started = synchronized(lifecycleLock) { running } ?: return
        stopRun(started)
    }

    private fun requireActiveRun(started: RunningOpenFlux) {
        synchronized(lifecycleLock) {
            if (running !== started || started.stopping.get()) {
                throw CancellationException("OpenFlux start was stopped")
            }
            check(started.process.isAlive) {
                "OpenFlux exited before the encrypted transport became ready"
            }
        }
    }

    private fun stopRun(started: RunningOpenFlux) = synchronized(started) {
        started.stopping.set(true)
        val process = started.process
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(OPENFLUX_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(OPENFLUX_FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            started.outputThread?.interrupt()
            started.configFile.delete()
            if (!process.isAlive) {
                synchronized(lifecycleLock) {
                    if (running === started) running = null
                }
            }
        }
        check(!process.isAlive) { "OpenFlux process is still stopping after a forced stop" }
    }

    private data class RunningOpenFlux(
        val process: Process,
        val configFile: File,
        val ready: AtomicBoolean = AtomicBoolean(false),
        val stopping: AtomicBoolean = AtomicBoolean(false),
        @Volatile var outputThread: Thread? = null
    )
}

internal fun sanitizeOpenFluxEngineOutput(
    line: String,
    config: OpenFluxProfileConfig,
    username: String,
    password: String
): String {
    val secrets = listOf(config.documentUrl, config.encryptionKey, username, password)
        .filter(String::isNotBlank)
        .sortedByDescending(String::length)
    val redacted = secrets.fold(line) { value, secret ->
        value.replace(secret, "<redacted>", ignoreCase = true)
    }
    return sanitizeDiagnosticLogLine("OpenFlux: $redacted")
}

private fun startOpenFluxOutputReader(process: Process, onLine: (String) -> Unit): Thread =
    thread(name = "OpenFluxLog", isDaemon = true) {
        try {
            consumeOpenFluxEngineOutput(process.inputStream, onLine)
        } catch (_: IOException) {
            if (process.isAlive && !Thread.currentThread().isInterrupted) {
                onLine("Process output reader stopped")
            }
        }
    }

internal fun consumeOpenFluxEngineOutput(input: InputStream, onLine: (String) -> Unit) {
    ProcessOutputInputStream(input).bufferedReader().use { reader ->
        val line = StringBuilder()
        var oversized = false
        fun emitLine() {
            onLine(if (oversized) "Process output line omitted (too long)" else line.toString().trimEnd('\r'))
            line.setLength(0)
            oversized = false
        }
        while (!Thread.currentThread().isInterrupted) {
            val character = reader.read()
            if (character == -1) break
            if (character == '\n'.code) {
                emitLine()
            } else if (line.length < OPENFLUX_MAX_LOG_LINE_CHARS) {
                line.append(character.toChar())
            } else {
                oversized = true
            }
        }
        if (line.isNotEmpty() || oversized) emitLine()
    }
}

private fun probeOpenFluxSocksListener(port: Int, username: String, password: String): Boolean =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(OPENFLUX_SOCKS_HOST, port), 250)
            socket.soTimeout = 500
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val method = if (username.isEmpty()) 0 else 2
            output.write(byteArrayOf(5, 1, method.toByte()))
            output.flush()
            if (input.read() != 5 || input.read() != method) return@use false
            if (method == 0) return@use true
            val user = username.toByteArray(Charsets.UTF_8)
            val pass = password.toByteArray(Charsets.UTF_8)
            if (user.size !in 1..255 || pass.size !in 1..255) return@use false
            output.write(byteArrayOf(1, user.size.toByte()))
            output.write(user)
            output.write(pass.size)
            output.write(pass)
            output.flush()
            input.read() == 1 && input.read() == 0
        }
    }.getOrDefault(false)

private const val OPENFLUX_SOCKS_HOST = "127.0.0.1"
private const val OPENFLUX_READY_MARKER = "OPENFLUX_READY"
private const val OPENFLUX_READY_TIMEOUT_MS = 90_000L
private const val OPENFLUX_READY_POLL_MS = 150L
private const val OPENFLUX_STOP_TIMEOUT_MS = 1_500L
private const val OPENFLUX_FORCE_STOP_TIMEOUT_MS = 1_000L
private const val OPENFLUX_MAX_LOG_LINE_CHARS = 4_096
