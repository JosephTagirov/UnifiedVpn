package org.olcbox.app.vpn.service

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    log: (String) -> Unit,
    verificationContext: OpenFluxVerificationContext? = null
): SocksBackedVpnEngine {
    val session = OpenFluxProfileConfig.parse(
        profile.rawConfig?.takeIf(String::isNotBlank) ?: profile.uri.orEmpty()
    )?.isSession == true
    return OpenFluxVpnEngine(
    profile = profile,
    socksPort = socksPort,
    username = username,
    password = password,
    log = log,
    browserPreflightSupported = Build.VERSION.SDK_INT >= 28 && !session,
    prepareBrowser = { documentUrl ->
        val requestContext = verificationContext ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Required)
        OpenFluxBrowserVerifier(context, log).prepare(documentUrl, requestContext)
    },
    prepareRuntime = {
        val nativeDirectory = context.applicationInfo.nativeLibraryDir?.let(::File)
        val executable = nativeDirectory?.resolve(if (session) "libopenflux_session.so" else "libopenflux.so")
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
}

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
        ::startOpenFluxOutputReader,
    private val browserPreflightSupported: Boolean = true,
    private val prepareBrowser: suspend (String) -> OpenFluxBrowserLease = {
        throw OpenFluxBrowserException(OpenFluxBrowserFailure.Required)
    }
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
    private var starting: StartingOpenFlux? = null

    override suspend fun start(): Unit = coroutineScope {
        stop()
        require(socksPort in 1..65535) { "Invalid OpenFlux SOCKS port" }
        val rawConfig = profile.rawConfig?.takeIf(String::isNotBlank) ?: profile.uri.orEmpty()
        val config = OpenFluxProfileConfig.parse(rawConfig)?.normalized()
            ?.takeIf(OpenFluxProfileConfig::isValid)
            ?: throw IllegalArgumentException("OpenFlux profile is invalid")
        val coroutineContext = currentCoroutineContext()
        val attempt = StartingOpenFlux(checkNotNull(coroutineContext[Job]))
        synchronized(lifecycleLock) {
            coroutineContext.ensureActive()
            starting = attempt
        }
        var lease: OpenFluxBrowserLease? = null
        val started = try {
            val verifiedLease = if (browserPreflightSupported && !config.isSession) {
                withTimeoutOrNull(OPENFLUX_BROWSER_PREFLIGHT_TIMEOUT_MS) {
                    prepareBrowser(config.documentUrl)
                } ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Timeout)
            } else null
            lease = verifiedLease
            synchronized(lifecycleLock) {
                coroutineContext.ensureActive()
                if (starting !== attempt) throw CancellationException("OpenFlux verification was stopped")
                verifiedLease?.requireCurrent()
                attempt.lease = verifiedLease
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
                    verifiedLease?.requireCurrent()
                    RunningOpenFlux(
                        process = startProcess(
                            listOf(
                                files.executable.absolutePath, "--config", configFile.absolutePath,
                                if (config.isSession) "--control-stdio" else "--bootstrap-stdio"
                            ),
                            files.workDirectory
                        ),
                        configFile = configFile,
                        browserLease = verifiedLease,
                        browserScope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
                    ).also { running = it }
                } catch (failure: Throwable) {
                    configFile.delete()
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            lease?.invalidate()
            (failure as? OpenFluxBrowserException)?.let { openFluxBrowserFailureLogLines(it).forEach(log) }
            // Exception text from WebView or IPC must never include a document URL or token.
            if (failure is CancellationException || failure is OpenFluxBrowserException || lease != null || !browserPreflightSupported || config.isSession) throw failure
            throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed)
        } finally {
            synchronized(lifecycleLock) { if (starting === attempt) starting = null }
        }

        try {
            started.browserLease?.let { browserLease ->
                started.browserScope.launch {
                    repeat((OPENFLUX_BROWSER_LEASE_MS / 250).toInt()) {
                        delay(250)
                        if (runCatching { browserLease.requireCurrent() }.isFailure) return@launch
                    }
                    browserLease.invalidate()
                }
            }
            started.outputThread = startOutputReader(started.process) output@{ line ->
                if (started.stopping.get()) return@output
                val browserRequest = openFluxBrowserRequestId(line)
                if (browserRequest != null && !config.isSession) {
                    requestBrowserVerification(started, config, browserRequest)
                } else if (line.startsWith(OPENFLUX_BROWSER_MARKER)) {
                    log("OpenFlux: Invalid browser verification request omitted")
                } else if (line == OPENFLUX_READY_MARKER && !started.stopping.get()) {
                    started.ready.set(true)
                } else {
                    val browserSecrets = synchronized(started.browserSecrets) {
                        if (started.stopping.get()) return@output
                        started.browserSecrets.toList()
                    }
                    val sanitized = sanitizeOpenFluxEngineOutput(line, config, username, password, browserSecrets)
                    if (line.startsWith("OPENFLUX_FATAL:")) {
                        started.nativeFailure = sanitized.removePrefix("OpenFlux: OPENFLUX_FATAL:").trim()
                            .takeIf(String::isNotEmpty)
                    }
                    log(sanitized)
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
            check(ready) {
                started.browserFailure?.diagnostic
                    ?: "OpenFlux encrypted transport did not become ready within 120 seconds"
            }
            log("OpenFlux encrypted transport ready on $socksHost:$socksPort")
        } catch (failure: Throwable) {
            runCatching { stopRun(started) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun stop() {
        val started = synchronized(lifecycleLock) {
            starting?.let {
                starting = null
                it.lease?.invalidate()
                it.job.cancel(CancellationException("OpenFlux verification was stopped"))
            }
            running
        } ?: return
        stopRun(started)
    }

    private suspend fun requireActiveRun(started: RunningOpenFlux) {
        fun requireCurrent() = synchronized(lifecycleLock) {
            if (running !== started || started.stopping.get()) {
                throw CancellationException("OpenFlux start was stopped")
            }
        }
        requireCurrent()
        // Exit can precede the last stdout callback. Bound the drain wait and
        // preserve cancellation rather than replacing the native reason with "exited".
        if (!started.process.isAlive && started.nativeFailure == null) {
            withTimeoutOrNull(500L) {
                while (started.outputThread?.isAlive == true && started.nativeFailure == null) {
                    currentCoroutineContext().ensureActive()
                    requireCurrent()
                    delay(25)
                }
            }
        }
        synchronized(lifecycleLock) {
            requireCurrent()
            started.nativeFailure?.let { error(it) }
            check(started.process.isAlive) {
                started.browserFailure?.diagnostic
                    ?: "OpenFlux exited before the encrypted transport became ready"
            }
        }
    }

    private fun requestBrowserVerification(started: RunningOpenFlux, config: OpenFluxProfileConfig, id: String) {
        if (started.stopping.get() || !started.process.isAlive) return
        val admitted = started.verifying.compareAndSet(false, true)
        if (!admitted) {
            started.browserScope.launch {
                log("OpenFlux: ${OpenFluxBrowserFailure.RateLimited.diagnostic}")
                writeBrowserResponse(started, id, null)
            }
            return
        }
        started.browserScope.launch {
            try {
                val lease = started.browserLease ?: throw OpenFluxBrowserException(OpenFluxBrowserFailure.Unsupported)
                val session = lease.consume(config.documentUrl)
                currentCoroutineContext().ensureActive()
                if (started.stopping.get()) return@launch
                started.browserFailure = null
                synchronized(started.browserSecrets) {
                    started.browserSecrets.addAll(session.cookies.map { it.value }.filter(String::isNotEmpty))
                    session.classicDocument?.let { started.browserSecrets.addAll(it.secrets) }
                    while (started.browserSecrets.size > 128) started.browserSecrets.removeAt(0)
                }
                writeBrowserResponse(started, id, session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = (failure as? OpenFluxBrowserException)?.reason ?: OpenFluxBrowserFailure.Failed
                started.browserFailure = reason
                (failure as? OpenFluxBrowserException)?.let { openFluxBrowserFailureLogLines(it).forEach(log) }
                log("OpenFlux: ${reason.diagnostic}")
                writeBrowserResponse(started, id, null)
            }
        }
    }

    private fun writeBrowserResponse(started: RunningOpenFlux, id: String, session: OpenFluxBrowserSession?) {
        synchronized(started.browserOutputLock) {
            if (started.stopping.get() || !started.process.isAlive) return
            runCatching {
                started.process.outputStream.write((openFluxBrowserResponse(id, session?.cookies, session?.userAgent, session?.classicDocument) + "\n").toByteArray(Charsets.UTF_8))
                started.process.outputStream.flush()
            }.onFailure {
                if (!started.stopping.get()) log("OpenFlux: Browser verification response could not be delivered")
            }
        }
    }

    private fun stopRun(started: RunningOpenFlux) = synchronized(started) {
        started.stopping.set(true)
        started.browserLease?.invalidate()
        started.browserScope.cancel()
        val process = started.process
        try {
            if (process.isAlive) {
                runCatching {
                    process.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII))
                    process.outputStream.flush()
                }
                process.waitFor(OPENFLUX_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
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
            synchronized(started.browserSecrets) { started.browserSecrets.clear() }
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
        val browserLease: OpenFluxBrowserLease?,
        val browserScope: CoroutineScope,
        val ready: AtomicBoolean = AtomicBoolean(false),
        val stopping: AtomicBoolean = AtomicBoolean(false),
        val verifying: AtomicBoolean = AtomicBoolean(false),
        val browserOutputLock: Any = Any(),
        val browserSecrets: MutableList<String> = mutableListOf(),
        @Volatile var browserFailure: OpenFluxBrowserFailure? = null,
        @Volatile var nativeFailure: String? = null,
        @Volatile var outputThread: Thread? = null
    )

    private class StartingOpenFlux(val job: Job, var lease: OpenFluxBrowserLease? = null)
}

internal fun sanitizeOpenFluxEngineOutput(
    line: String,
    config: OpenFluxProfileConfig,
    username: String,
    password: String,
    browserSecrets: List<String> = emptyList()
): String {
    val secrets = (listOf(config.documentUrl, config.encryptionKey, username, password) + browserSecrets)
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
private const val OPENFLUX_READY_TIMEOUT_MS = 120_000L
private const val OPENFLUX_READY_POLL_MS = 150L
private const val OPENFLUX_STOP_TIMEOUT_MS = 1_500L
private const val OPENFLUX_FORCE_STOP_TIMEOUT_MS = 1_000L
private const val OPENFLUX_MAX_LOG_LINE_CHARS = 4_096
