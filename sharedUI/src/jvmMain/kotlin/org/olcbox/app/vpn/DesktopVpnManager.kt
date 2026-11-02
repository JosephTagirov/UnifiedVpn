package org.olcbox.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.logging.diagnosticOlcRtcRoomReference
import org.olcbox.app.data.logging.sanitizeDiagnosticLogLine
import org.olcbox.app.data.logging.sanitizeOlcRtcDiagnosticOutput
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.VkCallsBootstrap
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopAdBlockingProxy
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.DesktopOpenFluxConfig
import org.olcbox.app.vpn.desktop.DesktopOpenFluxBrowserBootstrap
import org.olcbox.app.vpn.desktop.OpenFluxBrowserProtocol
import org.olcbox.app.vpn.desktop.OpenFluxBrowserRequestGate
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.DesktopConnectionPreflight
import org.olcbox.app.vpn.desktop.DesktopSingBoxConfig
import org.olcbox.app.vpn.desktop.DesktopXrayConfig
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.LocalPortAllocator
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.WindowsTunController
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopVpnManager internal constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer(),
    private val probeOnly: Boolean = false,
    private val checkActiveVpn: suspend () -> Unit = DesktopConnectionPreflight::requireNoActiveVpn
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )


    @Volatile private var connectedLatencySession: ProfileLatencySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(DesktopSocksProxySettings())
    val socksProxySettings: StateFlow<DesktopSocksProxySettings> = _socksProxySettings.asStateFlow()

    private val _activeRoutingMode = MutableStateFlow<DesktopRoutingMode?>(null)
    val activeRoutingMode: StateFlow<DesktopRoutingMode?> = _activeRoutingMode.asStateFlow()

    val activePacUrl: String?
        get() = pacServer.url.takeIf {
            _activeRoutingMode.value == DesktopRoutingMode.SystemProxy && pacServer.boundPort != null
        }

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var processWatchJob: Job? = null
    private var adBlockingWatchJob: Job? = null
    private var adBlockingProxy: DesktopAdBlockingProxy? = null
    private var tunProcessWatchJob: Job? = null
    private var process: Process? = null
    private var sessionControlProcess: Process? = null
    private var vkCallsBootstrap: VkCallsBootstrap? = null
    private var bootstrapWatchJob: Job? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var activeDesktopMode: DesktopMode? = null
    private var activeSocksProxySettings: DesktopSocksProxySettings? = null
    @Volatile
    private var openFluxPingTunnel: OpenFluxConnectedTunnel? = null
    @Volatile
    private var generation = 0L
    private val linuxTunController = LinuxTunController(::addLog)
    private val windowsTunController = WindowsTunController(::addLog)

    init {
        scope.launch {
            mutex.withLock {
                runCatching { if (!probeOnly) proxyController.recover() }
                    .onFailure { addLog("Proxy recovery failed: ${it.message}") }
            }
        }
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                        _status.value is VpnStatus.Connecting ||
                        _status.value is VpnStatus.Reconnecting ||
                        process != null ||
                        tunProcess != null

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting desktop VPN for selected location")
                    stopDesktopMode(finalStatus = false)

                    if (requestGeneration != generation) return@withLock
                }

                startDesktopMode(requestGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }
        }
    }

    override suspend fun ping(
        locationConfig: LocationConfig,
        profile: VpnProfileConfig
    ): Long? {
        val connected = connectedLatencySession?.takeIf { _status.value is VpnStatus.Connected && it.matches(profile,locationConfig) }
        if (connected != null) return ProfileLatency.connected(connected,{ connectedLatencySession })
        if (profile.isOlcRtc() && !locationConfig.usesModernRtcEngine()) return OlcRtcConnectionChecker.ping(locationConfig,locationsRepository.getDeviceIdentity())
        ExternalAwgGuard.requireNotConnected(profile)
        return DesktopProfileLatency.ping(locationsRepository,locationConfig,profile)
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        if (locationConfig.usesModernRtcEngine()) return DesktopProfileLatency.ping(
            locationsRepository, locationConfig, org.olcbox.app.data.model.LocationEntry.from("rtc-check", locationConfig).profile)
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val socks = activeSocksProxySettings ?: _socksProxySettings.value.normalized()
        return SubscriptionFetchProxy(
            host = socks.host,
            port = socks.port,
            username = socks.username,
            password = socks.password
        )
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = _socksProxySettings.value.copy(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(
            socksHost = normalized.host,
            socksPort = normalized.port,
            socksUsername = normalized.username,
            socksPassword = normalized.password
        )
    }

    fun close() {
        runBlocking {
            generation++

            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }

            scope.cancel()
        }
    }

    private suspend fun startDesktopMode(requestGeneration: Long, isRestart: Boolean) {
        connectedLatencySession = null
        openFluxPingTunnel = null
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()?.normalized()
        if (active == null || !active.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }
        val profile = active.profile.normalized()
        val isAmneziaProfile = profile.normalizedType == VpnProfileConfig.TYPE_AMNEZIA_WG ||
            profile.normalizedType == VpnProfileConfig.TYPE_AMNEZIA_VPN
        val isVlessProfile = profile.normalizedType == VpnProfileConfig.TYPE_VLESS &&
            runCatching { DesktopXrayConfig.supports(profile) }.getOrDefault(false)
        val isSupportedWindowsProfile = DesktopPaths.os == DesktopOs.Windows &&
            (isAmneziaProfile || isVlessProfile || profile.isOpenFlux())
        if (!profile.isOlcRtc() && !isSupportedWindowsProfile) {
            val message = "${profile.typeLabel()} profiles are not supported by the desktop engine"
            setStatus(VpnStatus.Error(message))
            addLog(message)
            return
        }
        val location = active.location.normalized()
        if (profile.isOlcRtc() && !location.hasValidCryptoKey()) {
            val message = "olcRTC key must be 64 hexadecimal characters"
            setStatus(VpnStatus.Error(message))
            addLog(message)
            return
        }
        val engineName = if (profile.isOlcRtc()) "olcRTC" else profile.typeLabel()

        try {
            // Check before DNS, port allocation, bootstrap channels or engine startup.
            val systemProxyActive = if (probeOnly) false else {
                checkActiveVpn()
                proxyController.hasActiveProxy()
            }
            if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
            val configuredSocksSettings = _socksProxySettings.value.normalized()
            val usesProxySettings = profile.usesProxyRoutingSettings()
            val requestedMode = configuredSocksSettings.routingModeFor(usesProxySettings)
            val resolvedMode = configuredSocksSettings.resolvedRoutingMode(
                DesktopPaths.os, usesProxySettings, systemProxyActive
            )
            check(!systemProxyActive || resolvedMode == DesktopRoutingMode.LocalSocks) {
                ConnectionPreflight.PROXY_ACTIVE
            }
            if (resolvedMode == DesktopRoutingMode.SystemProxy) {
                require(configuredSocksSettings.username.isBlank() && configuredSocksSettings.password.isBlank()) {
                    "Chrome system proxy does not support SOCKS authentication; use local SOCKS mode with credentials"
                }
            }
            var desktopMode = DesktopMode.from(resolvedMode, usesProxySettings)
            check(!probeOnly || desktopMode == DesktopMode.LocalSocks) { "Latency probe requires loopback mode" }
            if (requestedMode == DesktopRoutingMode.Auto) {
                addLog("Auto selected ${resolvedMode.displayName()}")
                if (systemProxyActive) addLog("Keeping the existing system proxy; use the local SOCKS endpoint in your application")
            }
            val bypassHosts = when {
                isAmneziaProfile -> listOf(DesktopSingBoxConfig.endpointHost(profile))
                isVlessProfile -> DesktopXrayConfig.endpointHosts(profile)
                else -> emptyList()
            }
            val ready = CompletableDeferred<Unit>()
            val startupFailure = CompletableDeferred<String>()
            val selectedSocksPort = LocalPortAllocator.select(
                host = configuredSocksSettings.host,
                preferredPort = configuredSocksSettings.port,
                excludedPorts = setOf(PacServer.PAC_PORT)
            )
            val socksSettings = configuredSocksSettings.copy(port = selectedSocksPort)
            activeSocksProxySettings = socksSettings
            val filtering = socksSettings.blockAds
            val engineSocksSettings = if (filtering) socksSettings.copy(
                host = "127.0.0.1",
                port = LocalPortAllocator.select("127.0.0.1", if (selectedSocksPort < 65535) selectedSocksPort + 1 else 10809,
                    excludedPorts = setOf(selectedSocksPort, PacServer.PAC_PORT))
            ) else socksSettings
            if (selectedSocksPort != configuredSocksSettings.port) {
                addLog(
                    "Local SOCKS port ${configuredSocksSettings.port} is occupied; " +
                        "using $selectedSocksPort for this connection"
                )
            }
            activeDesktopMode = desktopMode
            _activeRoutingMode.value = resolvedMode

            if (DesktopPaths.os == DesktopOs.Windows && profile.isOlcRtc() && location.isVkCalls()) {
                VkCallsBootstrap.findProfile(active.subscriptionUrl,locationsRepository.getAllLocations())?.let { supportProfile ->
                    addLog("VK Calls: preparing secure support channel")
                    vkCallsBootstrap = try {
                        VkCallsBootstrap.start(supportProfile,setOf(selectedSocksPort,engineSocksSettings.port,PacServer.PAC_PORT))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        addLog("VK Calls support is unavailable; trying direct signaling")
                        null
                    }
                    vkCallsBootstrap?.let { addLog("VK Calls support SOCKS: 127.0.0.1:${it.port}") }
                    if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
                }
            }

            // Pin the exact endpoint before adding capture routes. Resolving it
            // again inside the engine could select another address and loop.
            val pinnedEndpoints = if (desktopMode == DesktopMode.WindowsTun) {
                require(isAmneziaProfile || isVlessProfile) { "This profile only supports proxy modes on Windows" }
                require(socksSettings.host == PacServer.LOCAL_SOCKS_HOST) { "Windows TUN requires SOCKS on 127.0.0.1" }
                bypassHosts.associateWith { host -> InetAddress.getAllByName(host)
                    .sortedBy { if (it is Inet4Address) 0 else 1 }
                    .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
                    ?.hostAddress ?: error("Could not resolve the VPN server for TUN") }
            } else emptyMap()
            val pinnedEndpoint = bypassHosts.firstOrNull()?.let(pinnedEndpoints::get)
            val pinnedDownloadEndpoint = if (isVlessProfile) {
                DesktopXrayConfig.downloadEndpointHost(profile)?.let(pinnedEndpoints::get)
            } else null

            process = when {
                profile.isOlcRtc() -> startOlcRtcWithRetries(
                    location = location,
                    socksSettings = engineSocksSettings,
                    requestGeneration = requestGeneration,
                    privileged = desktopMode == DesktopMode.LinuxTun
                )
                isVlessProfile -> startXrayProcess(
                    profile = profile,
                    socksSettings = engineSocksSettings,
                    startupFailure = startupFailure,
                    logOutput = true,
                    endpointAddress = pinnedEndpoint,
                    downloadEndpointAddress = pinnedDownloadEndpoint
                )
                profile.isOpenFlux() -> startOpenFluxProcess(
                    profile = profile,
                    socksSettings = engineSocksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = true
                )
                else -> startSingBoxProcess(
                    profile = profile,
                    socksSettings = engineSocksSettings,
                    startupFailure = startupFailure,
                    logOutput = true,
                    endpointAddress = pinnedEndpoint
                )
            }

            val engineProcess = process ?: error("$engineName process is missing")
            if (!profile.isOlcRtc()) {
                waitForOlcRtcReady(
                    process = engineProcess,
                    ready = ready,
                    startupFailure = startupFailure,
                    socksPort = engineSocksSettings.port,
                    requestGeneration = requestGeneration,
                    engineName = engineName,
                    requireReadySignal = profile.isOpenFlux(),
                    timeoutMs = if (profile.isOpenFlux()) OPENFLUX_READY_TIMEOUT_MS else OLC_READY_TIMEOUT_MS
                )
            }

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (filtering) {
                val filter = DesktopAdBlockingProxy()
                adBlockingProxy = filter
                filter.start(socksSettings, engineSocksSettings, preserveUpstreamDns = isAmneziaProfile)
                addLog("Ad blocking enabled (local domain filter)")
                val filterProcess = filter.process ?: error("Ad blocking filter is missing")
                adBlockingWatchJob = scope.launch {
                    val exit = waitForProcessExit(filterProcess) ?: return@launch
                    if (!isActive) return@launch
                    scope.launch {
                        mutex.withLock {
                            if (requestGeneration == generation && adBlockingProxy === filter) {
                                handleUnexpectedProcessExit("Ad blocking filter stopped ($exit)",
                                    "Ad blocking filter stopped", requestGeneration)
                            }
                        }
                    }
                }
            }

            if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(socksSettings, requestGeneration)
                DesktopMode.WindowsTun -> startWindowsTun(
                    socksSettings,
                    requestGeneration,
                    pinnedEndpoints.values.distinct()
                )
                DesktopMode.SystemProxy -> {
                    try {
                        startSystemProxy(socksSettings, requestGeneration)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        if (requestedMode != DesktopRoutingMode.Auto) throw error
                        // Only fall back after our proxy lease has been restored.
                        proxyController.restore()
                        pacServer.stop()
                        desktopMode = DesktopMode.LocalSocks
                        activeDesktopMode = desktopMode
                        _activeRoutingMode.value = DesktopRoutingMode.LocalSocks
                        addLog("System proxy is unavailable; Auto selected Local SOCKS only")
                    }
                }
                DesktopMode.LocalSocks -> Unit
            }

            if (!engineProcess.isAlive) {
                error("$engineName exited before desktop proxy was enabled")
            }

            startProcessExitWatchers(
                desktopMode = desktopMode,
                engineProcess = engineProcess,
                engineName = engineName,
                currentTunProcess = tunProcess,
                requestGeneration = requestGeneration
            )

            openFluxPingTunnel = OpenFluxConnectedTunnel.from(
                profile,
                SubscriptionFetchProxy(
                    host = socksSettings.host,
                    port = socksSettings.port,
                    username = socksSettings.username,
                    password = socksSettings.password
                )
            )
            connectedLatencySession = ProfileLatencySession(profile,location,
                SubscriptionFetchProxy(socksSettings.host,socksSettings.port,socksSettings.username,socksSettings.password))
            setStatus(VpnStatus.Connected)
            addLog(
                when (desktopMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN connected"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN connected"
                    DesktopMode.SystemProxy -> "Desktop proxy connected"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy connected"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            stopDesktopMode(finalStatus = false)

            if (e !is CancellationException && requestGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
            }
        }
    }

    private suspend fun startLinuxTun(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(
            hevBinary = hevBinary,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
    }

    private suspend fun startWindowsTun(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long,
        bypassHosts: List<String>
    ) {
        val helperBinary = DesktopNativeAssets.resolveWindowsTunHelper()
        tunProcess = windowsTunController.start(
            helperBinary = helperBinary,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password,
            bypassHosts = bypassHosts,
            shouldContinue = { requestGeneration == generation }
        )

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("tun2socks process is missing"))
    }

    private suspend fun startSystemProxy(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        pacServer.start(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )
        pacServer.boundPort?.takeIf { it != PacServer.PAC_PORT }?.let { port ->
            addLog("PAC port ${PacServer.PAC_PORT} is occupied; using $port")
        }
        proxyController.enable(pacServer.url)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }
    }

    private enum class DesktopMode {
        LinuxTun,
        WindowsTun,
        SystemProxy,
        LocalSocks;

        companion object {
            fun from(mode: DesktopRoutingMode, usesProxySettings: Boolean): DesktopMode {
                return when (mode.resolveForCurrentPlatform(usesProxySettings)) {
                    DesktopRoutingMode.Tun -> when (DesktopPaths.os) {
                        DesktopOs.Linux -> LinuxTun
                        DesktopOs.Windows -> WindowsTun
                        DesktopOs.Other -> SystemProxy
                    }
                    DesktopRoutingMode.SystemProxy -> SystemProxy
                    DesktopRoutingMode.LocalSocks -> LocalSocks
                    DesktopRoutingMode.Auto -> error("Auto desktop mode was not resolved")
                }
            }
        }
    }

    private suspend fun startOlcRtcWithRetries(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long,
        privileged: Boolean
    ): Process = retryOlcRtcStartup(
        isCurrent = { requestGeneration == generation },
        onRetry = { retry, delayMillis ->
            setStatus(VpnStatus.Reconnecting)
            addLog("Temporary olcRTC start failure; retry $retry/3 in ${delayMillis / 1_000}s")
        }
    ) {
        // Each attempt owns its readiness signals; a late line from an old core cannot ready the next one.
        val ready = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<String>()
        val started = startOlcRtcProcessWithFallback(
            location, socksSettings, ready, failure, logOutput = true, privileged = privileged
        )
        process = started
        try {
            waitForOlcRtcReady(
                process = started,
                ready = ready,
                startupFailure = failure,
                socksPort = socksSettings.port,
                requestGeneration = requestGeneration
            )
            if (location.usesModernRtcEngine()) {
                check(VkCallsRuntimeConfig.probeSocks(socksSettings.port, socksSettings.username, socksSettings.password)) {
                    "VK Calls SOCKS authentication failed"
                }
            }
            started
        } catch (error: Exception) {
            stopProcess(started)
            check(!started.isAlive) { "Previous olcRTC process did not stop; refusing a second instance" }
            logJob?.cancel()
            logJob = null
            process = null
            deleteOlcRtcConfig()
            throw error
        }
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean
    ): Process {
        val binaries = if (location.usesModernRtcEngine()) listOf(DesktopNativeAssets.resolveVkCallsBinary())
            else DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        val dnsServer = normalizeOlcRtcDnsEndpoint(
            location.dnsServer.ifBlank { DesktopDnsResolver.current() }
        )
        var lastException: Exception? = null

        addLog("Using DNS server $dnsServer for olcRTC")

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = logOutput,
                    privileged = privileged,
                    dnsServer = dnsServer
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) break

                addLog("olcRTC start failed for ${binary.fileName}: ${e.message}. Retrying with fallback binary.")
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private suspend fun stopDesktopMode(finalStatus: Boolean) {
        connectedLatencySession = null
        openFluxPingTunnel = null
        _activeRoutingMode.value = null
        vkCallsBootstrap?.close()
        vkCallsBootstrap = null
        if (_status.value is VpnStatus.Disconnected && process == null && tunProcess == null && adBlockingProxy == null) {
            cancelProcessJobs()
            activeSocksProxySettings = null
            return
        }

        setStatus(VpnStatus.Stopping)
        cancelProcessJobs()

        val stoppedMode = activeDesktopMode
        when (stoppedMode) {
            DesktopMode.LinuxTun -> {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.WindowsTun -> {
                runCatching {
                    windowsTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Windows TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.SystemProxy -> {
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }
            DesktopMode.LocalSocks,
            null -> Unit
        }

        if (stoppedMode == DesktopMode.SystemProxy) {
            pacServer.stop()
        }
        activeDesktopMode = null
        activeSocksProxySettings = null

        adBlockingProxy?.close()
        adBlockingProxy = null
        stopProcess(process)
        process = null
        deleteOlcRtcConfig()

        if (finalStatus) {
            setStatus(VpnStatus.Disconnected)
            addLog(
                when (stoppedMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN stopped"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN stopped"
                    DesktopMode.SystemProxy -> "Desktop proxy stopped"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy stopped"
                    null -> "Desktop connection stopped"
                }
            )
        }
    }

    private fun cancelProcessJobs() {
        adBlockingWatchJob?.cancel()
        adBlockingWatchJob = null
        bootstrapWatchJob?.cancel()
        bootstrapWatchJob = null
        processWatchJob?.cancel()
        processWatchJob = null

        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = null

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        dnsServer: String
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val dataDir = DesktopNativeAssets.resolveOlcRtcDataDir()
        val olcRtcCommand = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dnsServer = dnsServer,
            dataDir = dataDir
        )
        val configPath = if (config.usesModernRtcEngine()) {
            require(socksSettings.host == "127.0.0.1") { "VK Calls requires loopback SOCKS" }
            writeEngineClientConfig("vkcalls", VkCallsRuntimeConfig.build(
                config, socksSettings.port, socksSettings.username, socksSettings.password, dnsServer
            ))
        } else writeOlcRtcClientConfig(olcRtcCommand)
        val command = olcRtcCommand.args(configPath)

        addLog(
            "Starting olcRTC provider=$provider, transport=${config.transport}, " +
                "${diagnosticOlcRtcRoomReference(config.id)}, port=${socksSettings.port}"
        )

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"
        if (config.usesModernRtcEngine()) {
            listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")
                .forEach { processBuilder.environment().remove(it) }
            vkCallsBootstrap?.let { support ->
                processBuilder.environment()["HTTPS_PROXY"] = support.proxyUrl
                processBuilder.environment()["HTTP_PROXY"] = support.proxyUrl
            }
        }
        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        val diagnosticLine = if (config.usesModernRtcEngine()) VkCallsRuntimeConfig.sanitizedOutput(
                            vkCallsBootstrap?.sanitize(line) ?: line, config, socksSettings.username, socksSettings.password
                        ) else line
                        if (logOutput) {
                            val safeLine = if (config.usesModernRtcEngine()) diagnosticLine
                                else sanitizeOlcRtcDiagnosticOutput(line, config.id)
                            val message = "rtc: $safeLine"
                            addLog(message)
                            println(message)
                        }

                        if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                            ready.complete(Unit)
                        }

                        desktopOlcRtcStartupFailure(diagnosticLine)?.let(startupFailure::complete)
                    }
                }
            } catch (_: IOException) {
                // Process stdout may close while stopping or after a remote disconnect.
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    private fun startSingBoxProcess(
        profile: VpnProfileConfig,
        socksSettings: DesktopSocksProxySettings,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        endpointAddress: String? = null
    ): Process {
        val binary = DesktopNativeAssets.resolveSingBoxBinary()
        val configPath = writeEngineClientConfig(
            prefix = "sing-box",
            config = DesktopSingBoxConfig.build(profile, socksSettings, endpointAddress)
        )
        val command = listOf(
            binary.toAbsolutePath().toString(),
            "run",
            "-c",
            configPath.toAbsolutePath().toString()
        )

        return startNativeProxyProcess(
            command = command,
            profile = profile,
            engineName = "sing-box",
            configPath = configPath,
            startupFailure = startupFailure,
            logOutput = logOutput
        )
    }

    private fun startXrayProcess(
        profile: VpnProfileConfig,
        socksSettings: DesktopSocksProxySettings,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        endpointAddress: String? = null,
        downloadEndpointAddress: String? = null
    ): Process {
        val binary = DesktopNativeAssets.resolveXrayBinary()
        val configPath = writeEngineClientConfig(
            prefix = "xray",
            config = DesktopXrayConfig.build(profile, socksSettings, endpointAddress, downloadEndpointAddress)
        )
        val command = listOf(
            binary.toAbsolutePath().toString(),
            "run",
            "-config",
            configPath.toAbsolutePath().toString()
        )

        return startNativeProxyProcess(
            command = command,
            profile = profile,
            engineName = "xray",
            configPath = configPath,
            startupFailure = startupFailure,
            logOutput = logOutput
        )
    }

    private fun startNativeProxyProcess(
        command: List<String>,
        profile: VpnProfileConfig,
        engineName: String,
        configPath: Path,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        onOutput: suspend (String, Process) -> Boolean = { _, _ -> false }
    ): Process {

        addLog("Starting ${profile.typeLabel()} with $engineName")

        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"
        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        if (onOutput(line, startedProcess)) continue

                        if (logOutput) {
                            emitSanitizedDesktopProcessOutput(
                                source = engineName,
                                line = line,
                                logSink = ::addLog,
                                consoleSink = ::println
                            )
                        }

                        desktopNativeStartupFailure(line)?.let(startupFailure::complete)
                    }
                }
            } catch (_: IOException) {
                // Process stdout may close while stopping or after a remote disconnect.
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    private fun startOpenFluxProcess(
        profile: VpnProfileConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean
    ): Process {
        val config = DesktopOpenFluxConfig.build(profile, socksSettings)
        val openFlux = checkNotNull(OpenFluxProfileConfig.parse(
            profile.normalized().rawConfig?.takeIf(String::isNotBlank) ?: profile.normalized().uri.orEmpty()
        ))
        val browser = DesktopOpenFluxBrowserBootstrap(::addLog, interactive = logOutput)
        val browserRequests = OpenFluxBrowserRequestGate()
        val binary = DesktopNativeAssets.resolveOpenFluxBinary(session = openFlux.isSession)
        val configPath = writeEngineClientConfig(prefix = "openflux", config = config)
        return startNativeProxyProcess(
            command = DesktopOpenFluxConfig.args(binary, configPath, session = openFlux.isSession),
            profile = profile,
            engineName = "OpenFlux",
            configPath = configPath,
            startupFailure = startupFailure,
            logOutput = logOutput,
            onOutput = { line, nativeProcess ->
                val requestId = OpenFluxBrowserProtocol.requestId(line)
                if (requestId != null && !openFlux.isSession) {
                    val reply = if (browserRequests.allow(requestId)) {
                        addLog("OpenFlux is verifying anonymous browser access")
                        browser.verify(requestId, openFlux.documentUrl, nativeProcess::isAlive)
                    } else {
                        addLog("OpenFlux browser verification rate limit reached")
                        OpenFluxBrowserProtocol.failure(requestId)
                    }
                    if (nativeProcess.isAlive) {
                        nativeProcess.outputStream.write((reply + "\n").toByteArray(Charsets.UTF_8))
                        nativeProcess.outputStream.flush()
                    }
                }
                if (isDesktopOpenFluxReady(line)) ready.complete(Unit)
                line.startsWith("OPENFLUX_BROWSER_VERIFY")
            }
        ).also { if (openFlux.isSession) sessionControlProcess = it }
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        val path = DesktopPaths.writePrivateTempString(
            runtimeDir,
            "olcrtc-client-",
            ".yaml",
            command.yaml()
        )
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun writeEngineClientConfig(prefix: String, config: String): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        val path = DesktopPaths.writePrivateTempString(
            runtimeDir,
            "$prefix-",
            ".json",
            config
        )
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun deleteOlcRtcConfig() {
        olcRtcConfigPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcConfigPath = null
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            try {
                target.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        emitSanitizedDesktopProcessOutput(
                            source = "tun",
                            line = line,
                            logSink = ::addLog,
                            consoleSink = ::println
                        )
                    }
                }
            } catch (_: IOException) {
                // TUN stdout may close while the process is being stopped.
            }
        }
    }

    private fun startProcessExitWatchers(
        desktopMode: DesktopMode,
        engineProcess: Process,
        engineName: String,
        currentTunProcess: Process?,
        requestGeneration: Long
    ) {
        startEngineExitWatcher(engineProcess, engineName, requestGeneration)

        vkCallsBootstrap?.let { support ->
            bootstrapWatchJob?.cancel()
            bootstrapWatchJob = scope.launch {
                val exitCode = waitForProcessExit(support.process) ?: return@launch
                if (!isActive) return@launch
                scope.launch {
                    mutex.withLock {
                        if (requestGeneration != generation || vkCallsBootstrap !== support) return@withLock
                        handleUnexpectedProcessExit("VK Calls support channel stopped (code $exitCode)",
                            "VK Calls support channel is unavailable",requestGeneration)
                    }
                }
            }
        }

        when (desktopMode) {
            DesktopMode.LinuxTun,
            DesktopMode.WindowsTun -> startTunExitWatcher(
                currentTunProcess ?: error("TUN process is missing"),
                requestGeneration
            )
            DesktopMode.SystemProxy,
            DesktopMode.LocalSocks -> {
                tunProcessWatchJob?.cancel()
                tunProcessWatchJob = null
            }
        }
    }

    private fun startEngineExitWatcher(
        target: Process,
        engineName: String,
        requestGeneration: Long
    ) {
        processWatchJob?.cancel()
        processWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || process !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "$engineName process exited unexpectedly with code $exitCode",
                        errorMessage = "$engineName exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private fun startTunExitWatcher(target: Process, requestGeneration: Long) {
        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || tunProcess !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "TUN process exited unexpectedly with code $exitCode",
                        errorMessage = "TUN process exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private suspend fun handleUnexpectedProcessExit(
        logMessage: String,
        errorMessage: String,
        requestGeneration: Long
    ) {
        addLog(logMessage)
        stopDesktopMode(finalStatus = false)

        if (requestGeneration == generation) {
            setStatus(VpnStatus.Error(errorMessage))
        }
    }

    private fun waitForProcessExit(target: Process): Int? {
        return try {
            target.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        socksPort: Int,
        requestGeneration: Long? = null,
        engineName: String = "olcRTC",
        requireReadySignal: Boolean = true,
        timeoutMs: Long = OLC_READY_TIMEOUT_MS
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("$engineName failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (
                isDesktopEngineReady(
                    readinessSignalReceived = ready.isCompleted,
                    portAcceptsConnections = !requireReadySignal && canConnectToSocks(socksPort),
                    requireReadinessSignal = requireReadySignal
                )
            ) {
                waitForOlcRtcStartupStability(
                    process,
                    startupFailure,
                    requestGeneration,
                    engineName
                )
                return
            }

            if (!process.isAlive) {
                val failure = awaitDesktopStartupFailureAfterExit(startupFailure, logJob) {
                    requestGeneration == null || requestGeneration == generation
                }
                if (failure != null) {
                    error("$engineName failed before desktop proxy was enabled: $failure")
                }
                error("$engineName exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("$engineName start timed out")
    }

    private suspend fun waitForOlcRtcStartupStability(
        process: Process,
        startupFailure: CompletableDeferred<String>,
        requestGeneration: Long?,
        engineName: String = "olcRTC"
    ) {
        val deadline = System.currentTimeMillis() + OLC_STARTUP_STABILITY_MS
        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("$engineName failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (!process.isAlive) {
                val failure = awaitDesktopStartupFailureAfterExit(startupFailure, logJob) {
                    requestGeneration == null || requestGeneration == generation
                }
                if (failure != null) {
                    error("$engineName failed before desktop proxy was enabled: $failure")
                }
                error("$engineName exited before desktop proxy was enabled")
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port),
                    TCP_CONNECT_TIMEOUT_MS.toInt()
                )
            }
        }.isSuccess
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (target === sessionControlProcess) {
            sessionControlProcess = null
            if (target.isAlive) {
                runCatching {
                    target.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII))
                    target.outputStream.flush()
                    target.waitFor(1_500, TimeUnit.MILLISECONDS)
                }
            }
        }
        if (!target.isAlive) return

        target.toHandle().descendants().forEach {
            it.destroy()
        }

        target.destroy()

        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        val sanitized = org.olcbox.app.data.logging.LogPresentation.entry(message)
        _logs.update {
            (it + sanitized).takeLast(MAX_LOG_ENTRIES)
        }
    }

    companion object {
        internal fun latencyProbe(repository: LocationsRepository) = DesktopVpnManager(repository,
            proxyController=object : DesktopProxyController {
                override suspend fun enable(pacUrl: String) = error("Latency probe cannot change system proxy")
                override suspend fun restore() = Unit
            },probeOnly=true)

        const val MAX_LOG_ENTRIES = 5_000
        const val OLC_READY_TIMEOUT_MS = 25_000L
        const val OPENFLUX_READY_TIMEOUT_MS = 120_000L
        const val OLC_STARTUP_STABILITY_MS = 1_500L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4

    }
}

internal suspend fun awaitDesktopStartupFailureAfterExit(
    startupFailure: CompletableDeferred<String>,
    logReader: Job?,
    isCurrent: () -> Boolean
): String? {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Desktop start superseded")
    }

    checkCurrent()
    // Process exit can precede stderr delivery; a descendant may also keep the pipe open.
    if (logReader != null && !startupFailure.isCompleted) {
        withTimeoutOrNull(1_000L) {
            while (!logReader.isCompleted && !startupFailure.isCompleted) {
                checkCurrent()
                withTimeoutOrNull(200L) { logReader.join() }
            }
        }
    }
    checkCurrent()
    return if (startupFailure.isCompleted) startupFailure.await() else null
}

internal fun emitSanitizedDesktopProcessOutput(
    source: String,
    line: String,
    logSink: (String) -> Unit,
    consoleSink: (String) -> Unit
) {
    val sanitized = sanitizeDiagnosticLogLine("$source: $line")
    logSink(sanitized)
    consoleSink(sanitized)
}

internal fun isDesktopEngineReady(
    readinessSignalReceived: Boolean,
    portAcceptsConnections: Boolean,
    requireReadinessSignal: Boolean
): Boolean {
    return readinessSignalReceived || (!requireReadinessSignal && portAcceptsConnections)
}

internal fun isDesktopOpenFluxReady(line: String): Boolean = line.trim() == "OPENFLUX_READY"

internal fun desktopNativeStartupFailure(line: String): String? {
    return if (
        line.contains("FATAL", ignoreCase = true) ||
        line.contains("failed to start", ignoreCase = true)
    ) {
        sanitizeDiagnosticLogLine(line)
    } else {
        null
    }
}

internal fun desktopOlcRtcStartupFailure(line: String): String? {
    val text = line.lowercase()
    val friendlyMessage = friendlyOlcRtcFailure(line)
    return when {
        friendlyMessage != line -> friendlyMessage
        "failed to connect link" in text ||
            "join room failed" in text ||
            "get room token" in text && "failed" in text ||
            "transport connect" in text && "failed" in text -> line
        else -> null
    }
}
