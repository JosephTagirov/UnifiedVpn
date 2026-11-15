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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.ClientDirectRoutingConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.EntryFailoverPolicy
import org.olcbox.app.vpn.EntryRouteAcknowledgement
import org.olcbox.app.vpn.ExclusiveVpnEngineSession
import org.olcbox.app.vpn.VpnEntryChoice
import org.olcbox.app.vpn.VpnEntryFailoverPlanner
import org.olcbox.app.vpn.XrayEntryFailoverConfig
import org.olcbox.app.vpn.XraySocksSettings
import org.olcbox.app.vpn.service.EntryFailoverHealthProbe

/** A stable public SOCKS front whose private, acknowledged route follows real entry HTTPS probes. */
internal class DesktopEntryFailoverProcess(
    private val processStarter: (List<String>, Path) -> Process = { command, directory ->
        ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
    },
    private val nativeBinary: (String) -> Path = { source ->
        if (source == "awg") DesktopNativeAssets.resolveSingBoxBinary() else DesktopNativeAssets.resolveXrayBinary()
    },
    private val healthProbe: suspend (XraySocksSettings) -> Boolean = EntryFailoverHealthProbe::healthy,
    private val listenerReady: suspend (Process, Int, () -> Boolean) -> Unit = ::awaitReady,
    private val routingOverride: ((String) -> Boolean)? = null,
    private val policyFactory: (Long) -> EntryFailoverPolicy = { EntryFailoverPolicy(it) },
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val probeIntervalMillis: Long = 3_000
) : AutoCloseable {
    private val cleanupLock = Any()
    @Volatile private var closed = false
    private var started = false
    @Volatile var front: Process? = null
        private set
    @Volatile private var monitorJob: Job? = null
    private var scope: CoroutineScope? = null
    private var awgSession: ExclusiveVpnEngineSession? = null
    private val processes = mutableListOf<Process>()
    private val configPaths = mutableListOf<Path>()
    private val processConfigs = mutableMapOf<Process, Path>()
    private val processLogs = mutableMapOf<Process, Thread>()
    private var ownedDirectory: Path? = null
    private var apiSocket: Path? = null
    private var apiAddress: String? = null
    private var driveRoot: Path? = null
    private var xrayBinary: Path? = null
    private var awgBinary: Path? = null
    private var backends = emptyList<Backend>()

    val children: List<Process>
        get() = synchronized(cleanupLock) { backends.mapNotNull { it.process } }
    val isRunning: Boolean
        get() = !closed && front?.isAlive == true && monitorJob?.isActive == true
    @Volatile var selectedEntry: VpnEntryChoice? = null
        private set

    private class Backend(val choice: VpnEntryChoice, val socks: XraySocksSettings, val config: String) {
        @Volatile var process: Process? = null
        var lastStartedAt = Long.MIN_VALUE
    }

    suspend fun start(
        profile: VpnProfileConfig,
        socks: DesktopSocksProxySettings,
        entries: VpnEntryFailoverConfig,
        physicalInterface: String,
        shouldContinue: () -> Boolean,
        log: (source: String, line: String) -> Unit,
        excludedPorts: Set<Int> = emptySet(),
        wireGuardConfig: String? = null,
        ownsAwgSession: Boolean = true,
        runtimeDirectory: Path = DesktopPaths.appDataDir().resolve("runtime")
    ): Process {
        val wireGuard = profile.normalizedType in setOf(VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)
        val pair = requireNotNull(entries.normalizedOrNull()) { "Invalid entry pair" }
        // Managed entry pairs are literal addresses, independent of a temporarily redirected DNS alias.
        require(isIpLiteral(pair.primary.host) && isIpLiteral(pair.backup.host)) { "Desktop entry failover requires pinned entry addresses" }
        val raw = if (wireGuard) wireGuardConfig ?: DesktopSingBoxConfig.resolveWireGuardConfig(profile) else null
        synchronized(cleanupLock) {
            checkCurrent(shouldContinue)
            check(!started) { "Entry process owners can only start once" }
            started = true
            if (wireGuard && ownsAwgSession) awgSession = ExclusiveVpnEngineSession({
                DesktopSingBoxConfig.sessionIdentity(checkNotNull(raw))
            })
        }
        try {
            val launch: suspend () -> Unit = {
                startOwned(profile, socks, pair, physicalInterface, shouldContinue, log, excludedPorts, raw, runtimeDirectory)
            }
            val session = awgSession
            if (session == null) launch() else session.start(launch, ::closeResources)
            currentCoroutineContext().ensureActive()
            checkCurrent(shouldContinue)
            return checkNotNull(front)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private suspend fun startOwned(
        profile: VpnProfileConfig,
        socks: DesktopSocksProxySettings,
        entries: VpnEntryFailoverConfig,
        physicalInterface: String,
        shouldContinue: () -> Boolean,
        log: (String, String) -> Unit,
        excludedPorts: Set<Int>,
        raw: String?,
        runtimeDirectory: Path
    ) {
        val startupContext = currentCoroutineContext()
        val settings = socks.normalized()
        val allocated = (excludedPorts + setOf(settings.port, PacServer.PAC_PORT)).toMutableSet()
        fun privateSocks(): XraySocksSettings {
            val port = LocalPortAllocator.select("127.0.0.1", DesktopSocksProxySettings.MIN_PORT, allocated)
            allocated += port
            return XraySocksSettings("127.0.0.1", port, credential(), credential())
        }
        val primary = privateSocks()
        val backup = privateSocks()
        // The shared renderer retains its strict authenticated-loopback contract. Restore only
        // the existing Desktop user listener afterwards, preserving its configured LAN/auth mode.
        val renderedMain = XraySocksSettings("127.0.0.1", settings.port, credential(), credential())
        val wireGuard = raw != null
        synchronized(cleanupLock) {
            startupContext.ensureActive()
            checkCurrent(shouldContinue)
            val parent = DesktopPaths.ensurePrivateDirectory(runtimeDirectory.toAbsolutePath().normalize())
            val directory = DesktopPaths.ensurePrivateDirectory(Files.createTempDirectory(parent, "ef-"))
            ownedDirectory = directory
            driveRoot = directory.root
            val socket = directory.resolve("a.sock")
            apiSocket = socket
            apiAddress = socket.toAbsolutePath().toString().replace('\\', '/').let {
                if (it.length > 2 && it[1] == ':') it.substring(2) else it
            }
            require(checkNotNull(apiAddress).encodeToByteArray().size <= 100) { "Private entry control path is too long" }
            xrayBinary = nativeBinary("xray")
            checkCurrent(shouldContinue)
            if (wireGuard) {
                // Resolving extracts the bundled executable. Windows locks an executable while
                // a child is running, so the whole pair and later retries share this one result.
                awgBinary = nativeBinary("awg")
                checkCurrent(shouldContinue)
                backends = listOf(entries.primary to primary, entries.backup to backup).mapIndexed { index, (endpoint, proxy) ->
                    val effective = VpnEntryFailoverPlanner.forEndpoint(profile, endpoint, raw)
                    val childSettings = settings.copy(host = proxy.host, port = proxy.port,
                        username = proxy.username, password = proxy.password, blockAds = false)
                    val base = Json.parseToJsonElement(DesktopSingBoxConfig.build(effective, childSettings, endpoint.host)).jsonObject
                    val route = base.getValue("route").jsonObject
                    val bound = JsonObject(base + ("route" to JsonObject(route + ("default_interface" to JsonPrimitive(physicalInterface)))))
                    Backend(if (index == 0) VpnEntryChoice.PRIMARY else VpnEntryChoice.BACKUP, proxy, bound.toString())
                }
            }
        }
        val base = if (wireGuard) XrayEntryFailoverConfig.buildSocks(renderedMain, primary, backup, checkNotNull(apiAddress))
            else XrayEntryFailoverConfig.buildVless(profile, renderedMain, entries.primary, entries.backup,
                primary, backup, checkNotNull(apiAddress))
        val configured = configureDesktopFront(base, settings, physicalInterface, profile)
        val process = launchNative("xray", listOf("run", "-config"), configured, shouldContinue, log)
        listenerReady(process, settings.port) { !closed && shouldContinue() }
        synchronized(cleanupLock) {
            startupContext.ensureActive()
            checkCurrent(shouldContinue)
            val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = monitorScope
            monitorJob = monitorScope.launch {
                try {
                    monitor(primary, backup, entries, shouldContinue, log)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log("entry", "Entry controller stopped: ${failure.message ?: failure::class.simpleName}")
                    // The manager watches the stable front. A dead controller must not leave an
                    // apparently connected tunnel with an unknown last routing override.
                    runCatching { close() }
                }
            }
        }
        // The initial balancer selects entry-block. DIRECT can be used immediately;
        // the monitor enables the VPN branch only after a verified, acknowledged route.
        log("entry", "Direct access is ready; checking VPN entries in the background")
        startupContext.ensureActive()
        checkCurrent(shouldContinue)
    }

    private suspend fun monitor(
        primary: XraySocksSettings,
        backup: XraySocksSettings,
        entries: VpnEntryFailoverConfig,
        shouldContinue: () -> Boolean,
        log: (String, String) -> Unit
    ) {
        val policy = policyFactory(clockMillis())
        val route = EntryRouteAcknowledgement()
        var bothFailed = 0
        while (currentCoroutineContext().isActive) {
            checkCurrent(shouldContinue)
            check(front?.isAlive == true) { "Entry front exited" }
            for (backend in backends) if (backend.process?.isAlive != true &&
                (backend.lastStartedAt == Long.MIN_VALUE || clockMillis() - backend.lastStartedAt >= CHILD_RETRY_MS)) {
                try { startBackend(backend, shouldContinue, log) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { log("entry", "${backend.choice.name.lowercase()} AWG backend is unavailable; will retry") }
            }
            suspend fun probe(socks: XraySocksSettings): Boolean {
                if (backends.isNotEmpty() && backends.first { it.socks == socks }.process?.isAlive != true) return false
                return healthProbe(socks)
            }
            val health = coroutineScope {
                val first = async { probe(primary) }
                val second = async { probe(backup) }
                first.await() to second.await()
            }
            currentCoroutineContext().ensureActive()
            checkCurrent(shouldContinue)
            val decision = policy.observe(health.first, health.second, clockMillis())
            val desired = decision ?: policy.selected.takeIf {
                if (it == VpnEntryChoice.PRIMARY) health.first else health.second
            }
            if (desired != null && route.needsSelection(desired)) {
                val target = if (desired == VpnEntryChoice.PRIMARY) XrayEntryFailoverConfig.PRIMARY_TAG else XrayEntryFailoverConfig.BACKUP_TAG
                val confirmed = setOverride(target, shouldContinue)
                route.selected(desired, confirmed)
                if (confirmed) {
                    policy.switchedTo(desired, clockMillis())
                    selectedEntry = desired
                    log("entry", "Entry selected: ${if (desired == VpnEntryChoice.PRIMARY) entries.primary.name ?: "primary" else entries.backup.name ?: "backup"}")
                } else selectedEntry = null
            }
            bothFailed = if (!health.first && !health.second) bothFailed + 1 else 0
            if (bothFailed >= 2 && route.needsBlock()) {
                val confirmed = setOverride(XrayEntryFailoverConfig.BLOCK_TAG, shouldContinue)
                route.blocked(confirmed)
                selectedEntry = null
            }
            delay(probeIntervalMillis)
        }
    }

    private suspend fun startBackend(backend: Backend, shouldContinue: () -> Boolean, log: (String, String) -> Unit) {
        currentCoroutineContext().ensureActive()
        checkCurrent(shouldContinue)
        synchronized(cleanupLock) {
            checkCurrent(shouldContinue)
            backend.lastStartedAt = clockMillis()
            backend.process?.let(::retireNative)
            backend.process = null
        }
        val process = launchNative("awg", listOf("run", "-c"), backend.config, shouldContinue, log)
        synchronized(cleanupLock) {
            checkCurrent(shouldContinue)
            backend.process = process
        }
        listenerReady(process, backend.socks.port) { !closed && shouldContinue() }
    }

    private fun launchNative(source: String, arguments: List<String>, config: String,
                             shouldContinue: () -> Boolean, log: (String, String) -> Unit): Process = synchronized(cleanupLock) {
        checkCurrent(shouldContinue)
        val binary = if (source == "xray") checkNotNull(xrayBinary) else checkNotNull(awgBinary)
        checkCurrent(shouldContinue)
        // A prior failed launch may have written a config without publishing a process.
        // Retry its deletion before creating another secret-bearing file.
        for (orphan in configPaths.filterNot { it in processConfigs.values }.toList()) {
            Files.deleteIfExists(orphan)
            configPaths.remove(orphan)
        }
        val path = DesktopPaths.writePrivateTempString(checkNotNull(ownedDirectory), source + "-", ".json", config)
        configPaths.add(path)
        val process = try {
            processStarter(listOf(binary.toAbsolutePath().toString()) + arguments + path.toAbsolutePath().toString(), checkNotNull(driveRoot))
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(path); configPaths.remove(path) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        processes += process
        processConfigs[process] = path
        if (source == "xray") front = process
        processLogs[process] = thread(name = "DesktopEntry-$source", isDaemon = true) {
            try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { log(source, it) } } }
            catch (_: IOException) { /* Owned process shutdown closes the pipe. */ }
        }
        process
    }

    /** Keep every ownership record on failure so another cleanup cannot forget a live process. */
    private fun retireNative(process: Process) {
        stop(process)
        processConfigs[process]?.let { path -> Files.deleteIfExists(path); configPaths.remove(path) }
        processLogs.remove(process)?.interrupt()
        processConfigs.remove(process)
        processes.remove(process)
    }

    private fun setOverride(target: String, shouldContinue: () -> Boolean): Boolean = try {
        checkCurrent(shouldContinue)
        val custom = routingOverride
        val confirmed = if (custom != null) custom(target) else {
            val server = "--server=unix://${checkNotNull(apiAddress)}"
            command(listOf("api", "bo", server, "--timeout=3", "-b", XrayEntryFailoverConfig.BALANCER_TAG, target), shouldContinue)
            val output = command(listOf("api", "bi", server, "--timeout=3", "-json", XrayEntryFailoverConfig.BALANCER_TAG), shouldContinue)
            val start = output.indexOf('{')
            check(start >= 0) { "Entry control returned no state" }
            Json.parseToJsonElement(output.substring(start)).jsonObject["balancer"]?.jsonObject
                ?.get("override")?.jsonObject?.get("target")?.jsonPrimitive?.content == target
        }
        checkCurrent(shouldContinue)
        confirmed
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { checkCurrent(shouldContinue); false }

    private fun command(arguments: List<String>, shouldContinue: () -> Boolean): String {
        val process = synchronized(cleanupLock) {
            checkCurrent(shouldContinue)
            processStarter(listOf(checkNotNull(xrayBinary).toAbsolutePath().toString()) + arguments, checkNotNull(driveRoot))
                .also { processes += it }
        }
        try {
            check(process.waitFor(4, TimeUnit.SECONDS)) { "Private entry control timed out" }
            checkCurrent(shouldContinue)
            check(process.exitValue() == 0) { "Private entry control failed" }
            return process.inputStream.bufferedReader().readText()
        } finally {
            synchronized(cleanupLock) {
                stop(process)
                processes.remove(process)
            }
        }
    }

    override fun close() {
        closed = true
        val session = awgSession
        if (session == null) closeResources() else session.stop(::closeResources)
    }

    private fun closeResources(): Unit = synchronized(cleanupLock) {
        scope?.cancel()
        var failure: Throwable? = null
        for (process in processes.asReversed()) try { stop(process) } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        processLogs.values.forEach { it.interrupt() }
        if (failure == null) {
            configPaths.forEach { Files.deleteIfExists(it) }
            apiSocket?.let { Files.deleteIfExists(it); Files.deleteIfExists(it.resolveSibling(it.fileName.toString() + ".lock")) }
            ownedDirectory?.let { Files.deleteIfExists(it) }
            configPaths.clear(); processes.clear(); processConfigs.clear(); processLogs.clear(); backends = emptyList()
            front = null; monitorJob = null; scope = null; ownedDirectory = null; apiSocket = null
            selectedEntry = null
        }
        failure?.let { throw it }
    }

    private fun checkCurrent(shouldContinue: () -> Boolean) {
        if (closed || !shouldContinue()) throw CancellationException("Desktop entry start superseded")
    }

    companion object {
        private const val CHILD_RETRY_MS = 10_000L
        private fun credential() = UUID.randomUUID().toString().replace("-", "")
        private fun isIpLiteral(host: String) = ':' in host || host.split('.').let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull()?.let { value -> value in 0..255 } == true }
        }

        internal fun configureDesktopFront(base: String, settings: DesktopSocksProxySettings, physicalInterface: String,
                                           profile: VpnProfileConfig? = null): String {
            val root = Json.parseToJsonElement(base).jsonObject
            val inbounds = root.getValue("inbounds").jsonArray.map { element ->
                val inbound = element.jsonObject
                if (inbound["tag"] != JsonPrimitive("entry-main")) inbound else JsonObject(inbound + mapOf(
                    "listen" to JsonPrimitive(settings.host),
                    "settings" to buildJsonObject {
                        put("udp", true)
                        put("auth", if (settings.username.isBlank()) "noauth" else "password")
                        if (settings.username.isNotBlank()) put("accounts", buildJsonArray { add(buildJsonObject {
                            put("user", settings.username); put("pass", settings.password)
                        }) })
                    }
                ))
            }
            val outbounds = root.getValue("outbounds").jsonArray.map { element ->
                val outbound = element.jsonObject
                if (outbound["protocol"] != JsonPrimitive("vless")) outbound else {
                    val stream = outbound.getValue("streamSettings").jsonObject
                    val sockopt = stream["sockopt"]?.jsonObject ?: JsonObject(emptyMap())
                    JsonObject(outbound + ("streamSettings" to JsonObject(stream +
                        ("sockopt" to JsonObject(sockopt + ("interface" to JsonPrimitive(physicalInterface)))))))
                }
            }
            return ClientDirectRoutingConfig.apply(JsonObject(root + mapOf(
                "inbounds" to JsonArray(inbounds), "outbounds" to JsonArray(outbounds))).toString(), physicalInterface, profile)
        }

        private suspend fun awaitReady(process: Process, port: Int, current: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!current()) throw CancellationException("Entry start superseded")
                check(process.isAlive) { "Entry process exited before readiness" }
                if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return
                delay(50)
            }
            error("Entry listener did not become ready")
        }

        private fun stop(process: Process) {
            try {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        check(process.waitFor(2, TimeUnit.SECONDS)) { "Entry process did not stop" }
                    }
                }
                check(!process.isAlive) { "Entry process is still alive" }
            } finally {
                runCatching { process.outputStream.close() }; runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
            }
        }
    }
}
