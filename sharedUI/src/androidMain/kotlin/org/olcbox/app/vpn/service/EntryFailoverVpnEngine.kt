package org.olcbox.app.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.EntryFailoverPolicy
import org.olcbox.app.vpn.EntryRouteAcknowledgement
import org.olcbox.app.vpn.ManagedEntryDns
import org.olcbox.app.vpn.VpnEntryChoice
import org.olcbox.app.vpn.VpnEntryFailoverPlanner
import org.olcbox.app.vpn.XrayEntryFailoverConfig
import org.olcbox.app.vpn.XraySocksSettings
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One stable SOCKS/TUN, with endpoint selection affecting only newly created streams. */
internal class EntryFailoverVpnEngine(
    private val context: Context,
    private val profile: VpnProfileConfig,
    private val entries: VpnEntryFailoverConfig,
    private val wireGuardConfig: String?,
    override val socksPort: Int,
    username: String,
    password: String,
    private val log: (String) -> Unit,
    private val directRoutingEnabled: Boolean,
    private val backendFactory: (VpnProfileConfig, Int, String, String, (String) -> Unit) -> SocksBackedVpnEngine
) : SocksBackedVpnEngine {
    override val profileType = profile.normalizedType
    override val socksHost = "127.0.0.1"
    override val usesEntryFailover = true
    override val usesClientDirectRouting: Boolean = directRoutingEnabled
    override val isRunning: Boolean
        get() = !stopped && frontProcess?.isAlive == true && monitorJob?.isActive == true && canConnect(socksHost, socksPort)

    private val mainSocks = XraySocksSettings(socksHost, socksPort, username, password)
    private val wireGuard = profileType in setOf(VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)
    private val epoch = AtomicLong()
    private val resources = Any()
    private val cleanupLock = Any()
    private val retiringProcesses = mutableSetOf<Process>()
    private val retiringChildren = mutableSetOf<SocksBackedVpnEngine>()
    private val retiringJobs = mutableSetOf<Job>()
    private val retiringDirectories = mutableSetOf<File>()
    @Volatile private var stopped = true
    @Volatile private var frontProcess: Process? = null
    private var apiProcess: Process? = null
    private var scope: CoroutineScope? = null
    @Volatile private var monitorJob: Job? = null
    private var runtimeDir: File? = null
    private var logThread: Thread? = null
    private var executable: File? = null
    private var apiSocket: File? = null
    private val allocatedPorts = mutableSetOf(socksPort)
    private val primary = Backend(VpnEntryChoice.PRIMARY, entries.primary, privateSocks())
    private val backup = Backend(VpnEntryChoice.BACKUP, entries.backup, privateSocks())
    private var primaryAddresses = emptySet<String>()
    private var backupAddresses = emptySet<String>()
    private var lastResolutionAt = Long.MIN_VALUE

    private class Backend(val choice: VpnEntryChoice, val original: VpnEntryEndpoint, val socks: XraySocksSettings) {
        @Volatile var engine: SocksBackedVpnEngine? = null
        var pinned: VpnEntryEndpoint? = null
        var deadSamples = 0
        var failedProbes = 0
        var lastStartAt = Long.MIN_VALUE
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        stop()
        require(mainSocks.username.isNotBlank() && mainSocks.password.isNotBlank()) { "Entry failover requires private SOCKS credentials" }
        val token = epoch.incrementAndGet()
        stopped = false
        try {
            startEntryFailoverSession(
                directRoutingEnabled = directRoutingEnabled,
                startupTimeoutMillis = STARTUP_TIMEOUT_MS,
                startLocalFront = {
                    startFront(token)
                    waitForFront(token)
                },
                startHealthMonitor = { ready ->
                    val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    synchronized(resources) {
                        checkCurrent(token)
                        scope = monitorScope
                    }
                    val job = monitorScope.launch {
                        try {
                            monitor(token, ready)
                        } catch (cancelled: CancellationException) {
                            ready.cancel(cancelled)
                            throw cancelled
                        } catch (failure: Exception) {
                            ready.completeExceptionally(failure)
                            log("Entry failover monitor stopped: ${failure.message ?: failure::class.simpleName}")
                        }
                    }
                    synchronized(resources) {
                        checkCurrent(token)
                        monitorJob = job
                    }
                }
            )
            currentCoroutineContext().ensureActive()
            checkCurrent(token)
            log(if (directRoutingEnabled)
                "${profile.typeLabel()} local direct routing ready on $socksHost:$socksPort; VPN entries are checked in the background"
            else "${profile.typeLabel()} entry failover ready on $socksHost:$socksPort")
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        epoch.incrementAndGet()
        stopped = true
        synchronized(cleanupLock) {
            synchronized(resources) {
                scope?.cancel()
                scope = null
                monitorJob?.let { retiringJobs.add(it) }
                monitorJob = null
                retiringProcesses.addAll(listOfNotNull(apiProcess, frontProcess))
                apiProcess = null
                frontProcess = null
                retiringChildren.addAll(listOfNotNull(primary.engine, backup.engine))
                primary.engine = null
                backup.engine = null
                listOf(primary, backup).forEach {
                    it.pinned = null
                    it.deadSamples = 0
                    it.failedProbes = 0
                    it.lastStartAt = Long.MIN_VALUE
                }
                primaryAddresses = emptySet()
                backupAddresses = emptySet()
                lastResolutionAt = Long.MIN_VALUE
                runtimeDir?.let { retiringDirectories.add(it) }
                runtimeDir = null
            }
            var cleanupFailure: Exception? = null
            retiringProcesses.removeAll { process ->
                try { stopProcess(process); check(!process.isAlive); true }
                catch (failure: Exception) { cleanupFailure = failure; false }
            }
            // Stop promptly, then repeat after monitor completion to catch a child created during cancellation.
            retiringChildren.forEach { child -> runCatching { child.stop() } }
            retiringJobs.removeAll { job ->
                job.isCompleted || runBlocking { withTimeoutOrNull(5_000) { job.join(); true } } == true
            }
            if (retiringJobs.isEmpty()) retiringChildren.removeAll { child ->
                try { child.stop(); true } catch (failure: Exception) { cleanupFailure = failure; false }
            }
            logThread?.interrupt()
            logThread = null
            if (retiringJobs.isEmpty() && retiringProcesses.isEmpty() && retiringChildren.isEmpty()) {
                // Only this instance's randomly named private directories are removed.
                retiringDirectories.forEach { dir -> dir.listFiles()?.forEach { it.delete() }; dir.delete() }
                retiringDirectories.clear()
                executable = null
                apiSocket = null
            } else {
                throw IllegalStateException("Entry failover cleanup is still pending", cleanupFailure)
            }
        }
    }

    private suspend fun monitor(token: Long, ready: CompletableDeferred<Unit>) {
        val policy = EntryFailoverPolicy(SystemClock.elapsedRealtime())
        val route = EntryRouteAcknowledgement()
        var bothFailed = 0
        var reportedOffline = false
        while (currentCoroutineContext().isActive) {
            checkCurrent(token)
            if (wireGuard) {
                if (lastResolutionAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - lastResolutionAt >= DNS_REFRESH_MS) resolveEntries()
                restartUnavailableBackends(token, policy.selected)
                startAvailableBackends(token)
            }
            val health = coroutineScope {
                val first = async { probe(primary) }
                val second = async { probe(backup) }
                first.await() to second.await()
            }
            checkCurrent(token)
            primary.failedProbes = if (health.first) 0 else primary.failedProbes + 1
            backup.failedProbes = if (health.second) 0 else backup.failedProbes + 1
            // An overlapping AWG resolution is not evidence of an independently recovered primary.
            val primaryHealthy = health.first && (policy.selected == VpnEntryChoice.PRIMARY || !wireGuard || distinctEntries())
            val decision = policy.observe(primaryHealthy, health.second, SystemClock.elapsedRealtime())
            val desired = decision ?: policy.selected.takeIf {
                when (it) { VpnEntryChoice.PRIMARY -> primaryHealthy; VpnEntryChoice.BACKUP -> health.second }
            }
            if (desired != null && route.needsSelection(desired)) {
                val tag = if (desired == VpnEntryChoice.PRIMARY) PRIMARY_TAG else BACKUP_TAG
                val confirmed = setOverride(token, tag)
                route.selected(desired, confirmed)
                if (confirmed) {
                    policy.switchedTo(desired, SystemClock.elapsedRealtime())
                    ready.complete(Unit)
                    log("Entry selected: ${if (desired == VpnEntryChoice.PRIMARY) entries.primary.name ?: "primary" else entries.backup.name ?: "backup"}")
                }
            }
            bothFailed = if (!health.first && !health.second) bothFailed + 1 else 0
            if (bothFailed >= 2) {
                if (!reportedOffline) log("Both entry HTTPS probes are unavailable; waiting for a verified route")
                reportedOffline = true
                if (route.needsBlock()) route.blocked(setOverride(token, BLOCK_TAG))
            } else if (health.first || health.second) {
                reportedOffline = false
            }
            delay(PROBE_INTERVAL_MS)
        }
    }

    private suspend fun probe(backend: Backend): Boolean {
        if (wireGuard && backend.engine?.isRunning != true) return false
        return EntryFailoverHealthProbe.healthy(backend.socks)
    }

    private fun resolveEntries() {
        // The service binds this app process to the physical upstream before constructing the engine.
        val network = context.getSystemService(ConnectivityManager::class.java)?.boundNetworkForProcess
        fun addresses(host: String): Set<String> = runCatching {
            if (host == ManagedEntryDns.PRIMARY_HOST) return@runCatching setOf(resolveAndroidManagedPrimary(context))
            (network?.getAllByName(host) ?: InetAddress.getAllByName(host))
                .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .mapNotNull { it.hostAddress?.takeIf { address -> '%' !in address } }.toSet()
        }.getOrElse { emptySet() }
        primaryAddresses = addresses(entries.primary.host)
        backupAddresses = addresses(entries.backup.host)
        lastResolutionAt = SystemClock.elapsedRealtime()
    }

    private fun distinctEntries() = primaryAddresses.isNotEmpty() && backupAddresses.isNotEmpty() &&
        primaryAddresses.intersect(backupAddresses).isEmpty()

    private suspend fun startAvailableBackends(token: Long) {
        for (backend in listOf(primary, backup)) {
            if (backend.engine != null) continue
            val other = if (backend === primary) backup else primary
            if (other.engine != null && !distinctEntries()) continue
            val addresses = if (backend === primary) primaryAddresses else backupAddresses
            val address = addresses.firstOrNull { it != other.pinned?.host } ?: continue
            if (other.engine != null && other.pinned?.host in addresses) continue
            val now = SystemClock.elapsedRealtime()
            if (backend.lastStartAt != Long.MIN_VALUE && now - backend.lastStartAt < CHILD_RETRY_MS) continue
            backend.lastStartAt = now
            val pinned = backend.original.copy(host = address)
            val effective = VpnEntryFailoverPlanner.forEndpoint(profile, pinned, wireGuardConfig)
            val child = backendFactory(effective, backend.socks.port, backend.socks.username, backend.socks.password) {
                log("${backend.original.name ?: backend.choice.name}: $it")
            }
            synchronized(resources) {
                checkCurrent(token)
                backend.pinned = pinned
                backend.engine = child
            }
            try {
                child.start()
                currentCoroutineContext().ensureActive()
                checkCurrent(token)
                backend.deadSamples = 0
                backend.failedProbes = 0
            } catch (failure: Exception) {
                child.stop()
                synchronized(resources) { if (backend.engine === child) backend.engine = null }
                if (failure is CancellationException) throw failure
                checkCurrent(token)
                log("${backend.original.name ?: backend.choice.name} backend start failed: ${failure.message}")
            }
        }
    }

    private fun restartUnavailableBackends(token: Long, selected: VpnEntryChoice) {
        for (backend in listOf(primary, backup)) {
            val child = backend.engine ?: continue
            backend.deadSamples = if (child.isRunning || backend.failedProbes == 0) 0 else backend.deadSamples + 1
            val addresses = if (backend === primary) primaryAddresses else backupAddresses
            val addressChanged = backend.failedProbes >= 2 && addresses.isNotEmpty() && backend.pinned?.host !in addresses
            if (backend.deadSamples < 2 && !addressChanged) continue
            if (backend.deadSamples < 2 && backend.choice == selected &&
                (if (backend === primary) backup else primary).engine?.isRunning == true) continue
            if (SystemClock.elapsedRealtime() - backend.lastStartAt < CHILD_RETRY_MS) continue
            checkCurrent(token)
            child.stop()
            synchronized(resources) { if (backend.engine === child) backend.engine = null }
        }
    }

    private fun startFront(token: Long) {
        val primaryEndpoint = if (!wireGuard && entries.primary.host == ManagedEntryDns.PRIMARY_HOST)
            entries.primary.copy(host = resolveAndroidManagedPrimary(context)).also { log("RuWeb entry address: ${it.host}") }
        else entries.primary
        checkCurrent(token)
        val source = findEngineExecutable(context, "xray") ?: error("The packaged Xray core is missing")
        val binary = source.file ?: File(context.noBackupFilesDir, "engines/xray/${source.abi}/xray").also {
            it.parentFile?.mkdirs()
            copyAsset(context, source.assetPath.orEmpty(), it)
            it.setExecutable(true, true)
        }
        val dir = File(context.noBackupFilesDir, "ef/${UUID.randomUUID().toString().take(12)}").apply { mkdirs() }
        dir.setReadable(false, false); dir.setReadable(true, true)
        dir.setWritable(false, false); dir.setWritable(true, true)
        dir.setExecutable(false, false); dir.setExecutable(true, true)
        val socket = File(dir, "a.sock")
        val config = File(dir, "f.json")
        synchronized(resources) {
            try {
                checkCurrent(token)
                runtimeDir = dir
                apiSocket = socket
                executable = binary
                require(socket.absolutePath.toByteArray(Charsets.UTF_8).size < 100) { "Private API socket path is too long" }
                val base = if (wireGuard) XrayEntryFailoverConfig.buildSocks(mainSocks, primary.socks, backup.socks, socket.absolutePath)
                    else XrayEntryFailoverConfig.buildVless(profile.copy(entryFailover = null), mainSocks, primaryEndpoint, entries.backup,
                        primary.socks, backup.socks, socket.absolutePath)
                config.writeText(applyAndroidClientDirectRouting(base, directRoutingEnabled, profile))
                config.setReadable(false, false); config.setReadable(true, true)
                config.setWritable(false, false); config.setWritable(true, true)
                frontProcess = ProcessBuilder(binary.absolutePath, "run", "-config", config.absolutePath)
                    .directory(dir).redirectErrorStream(true).start()
                logThread = startEngineLogReader("EntryFailoverXrayLog", "entry-xray", checkNotNull(frontProcess), log)
            } catch (failure: Exception) {
                config.delete(); socket.delete(); dir.delete()
                throw failure
            }
        }
    }

    private suspend fun waitForFront(token: Long) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCurrent(token)
            check(frontProcess?.isAlive == true) { "Entry failover core exited before becoming ready" }
            if (canConnect(socksHost, socksPort)) return
            delay(100)
        }
        error("Entry failover SOCKS did not become ready")
    }

    private fun setOverride(token: Long, target: String): Boolean {
        return try {
            val server = "--server=unix://${checkNotNull(apiSocket).absolutePath}"
            command(token, listOf("api", "bo", server, "--timeout=3", "-b", BALANCER_TAG, target))
            val output = command(token, listOf("api", "bi", server, "--timeout=3", "-json", BALANCER_TAG))
            val objectStart = output.indexOf('{')
            check(objectStart >= 0) { "Routing API returned no state" }
            val selected = JSONObject(output.substring(objectStart)).optJSONObject("balancer")
                ?.optJSONObject("override")?.optString("target")
            check(selected == target) { "Routing API selection was not confirmed" }
            checkCurrent(token)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
            checkCurrent(token)
            log("Entry routing selection could not be confirmed: ${failure.message}")
            false
        }
    }

    private fun command(token: Long, arguments: List<String>): String {
        val process = synchronized(resources) {
            checkCurrent(token)
            ProcessBuilder(listOf(checkNotNull(executable).absolutePath) + arguments)
                .redirectErrorStream(true).start().also { apiProcess = it }
        }
        try {
            check(process.waitFor(4, TimeUnit.SECONDS)) { "Private routing API timed out" }
            checkCurrent(token)
            check(process.exitValue() == 0) { "Private routing API failed (${process.exitValue()})" }
            return process.inputStream.bufferedReader().readText()
        } finally {
            if (process.isAlive) stopProcess(process)
            synchronized(resources) { if (apiProcess === process) apiProcess = null }
        }
    }

    private fun checkCurrent(token: Long) {
        if (stopped || token != epoch.get()) throw CancellationException("Entry failover session stopped")
    }

    private fun privateSocks(): XraySocksSettings {
        var port: Int
        do { port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } } while (!allocatedPorts.add(port))
        return XraySocksSettings(port = port, username = randomCredential(), password = randomCredential())
    }

    private fun randomCredential() = UUID.randomUUID().toString().replace("-", "")
    private fun stopProcess(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(1_000, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1_000, TimeUnit.MILLISECONDS)
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private companion object {
        const val BALANCER_TAG = "entry-auto"
        const val PRIMARY_TAG = "entry-primary"
        const val BACKUP_TAG = "entry-backup"
        const val BLOCK_TAG = "entry-block"
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val PROBE_INTERVAL_MS = 10_000L
        const val DNS_REFRESH_MS = 30_000L
        const val CHILD_RETRY_MS = 15_000L
    }
}
