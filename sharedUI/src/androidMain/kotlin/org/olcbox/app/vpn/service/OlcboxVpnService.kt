package org.olcbox.app.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.olcbox.app.ui.activities.VpnProfileChooserActivity
import org.olcbox.app.ui.localization.androidUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mobile.Mobile
import mobile.SocketProtector
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.logging.diagnosticOlcRtcRoomReference
import org.olcbox.app.data.logging.sanitizeOlcRtcDiagnosticOutput
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelProfile
import org.olcbox.app.vpn.AndroidSplitTunnelProfiles
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import org.olcbox.app.vpn.UpstreamCandidate
import org.olcbox.app.vpn.UpstreamNetworkSelector
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.OlcRtcStartRetryPolicy
import org.olcbox.app.vpn.friendlyOlcRtcFailure
import org.olcbox.app.vpn.isRetryableOlcRtcStartFailure
import org.olcbox.app.vpn.notificationProfileTargetId
import org.olcbox.app.vpn.notificationSafeProfileName
import org.olcbox.app.vpn.selectOlcRtcDnsEndpoint
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

class OlcboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative(): Boolean
    private external fun isTun2socksRunningNative(): Boolean
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val olcRtcRuntime = Mobile.new_()
    private val olcRtcStartRetryPolicy = OlcRtcStartRetryPolicy(MAX_OLCRTC_START_RETRIES)
    private val mobileRuntimeCallLock = Any()
    private val lifecycleTransitionLock = Any()
    private val notificationProfileInputLock = Any()
    private val tunnelMutex = Mutex()
    private val notificationProfileSwitchMutex = Mutex()
    private val profileSelectionMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }
    private val deviceIdentityProvider by lazy {
        PersistentDeviceIdentityProvider(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var cleanupJob: Job? = null
    @Volatile
    private var cleanupTargetGeneration = 0L
    @Volatile
    private var cleanupShouldStopService = false
    @Volatile
    private var cleanupStopStartId = 0
    private var networkLossJob: Job? = null
    private var recoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var olcRtcStartRetryCount = 0
    @Volatile
    private var generation = 0L
    @Volatile
    private var lifecycleStopping = false
    @Volatile
    private var latestServiceStartId = 0
    private var recoveryRequestedForGeneration = 0L
    private var watchdogTunStats: Tun2SocksStats? = null
    private var watchdogStalledSamples = 0
    private var lastWakeLockRefreshAtMs = 0L
    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0
    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L
    @Volatile
    private var mobileRuntimeStarted = false
    private val notificationSwitchRequestId = AtomicLong(0L)
    private val notificationProfileStepAccumulator = AtomicLong(0L)
    private var notificationProfileDebounceJob: Job? = null
    @Volatile
    private var notificationForegroundStarted = false
    @Volatile
    private var notificationProfileCount = 0
    @Volatile
    private var activeProfileName = ""
    @Volatile
    private var activeProfileStorageId: String? = null
    @Volatile
    private var lastConnectedProfileStorageId: String? = null
    @Volatile
    private var connectedTunnelGeneration = -1L
    @Volatile
    private var sessionConnectedAtElapsedRealtimeMs: Long? = null
    @Volatile
    private var lastNotificationStatus = "Protecting your connection"

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var currentNetworkTransport: UpstreamTransport? = null
    private var isCallbackRegistered = false
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksListenHost = AndroidSocksProxySettings.DEFAULT_HOST
    private var socksListenPort = AndroidSocksProxySettings.DEFAULT_PORT
    private var socksUsername = ""
    private var socksPassword = ""
    private var splitTunnelMode = AndroidSplitTunnelMode.AllApps
    private var splitTunnelProxyApps = emptySet<String>()
    private var splitTunnelBypassApps = emptySet<String>()
    private var splitTunnelProfiles = AndroidSplitTunnelProfiles()
    private var socksProxy: AuthenticatedSocksProxy? = null
    private var externalEngine: SocksBackedVpnEngine? = null
    private var tunSocksBridge: SocksBackedVpnEngine? = null
    private var activeProfileType = VpnProfileConfig.TYPE_OLCRTC

    private data class StartOptions(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelProfiles: AndroidSplitTunnelProfiles
    )

    private data class Tun2SocksStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    private enum class MobileStartOutcome {
        Ready,
        RetryableFailure,
        FatalFailure,
        Superseded
    }

    private data class MobileStartResult(
        val outcome: MobileStartOutcome,
        val message: String = "",
        val rawMessage: String = message
    )

    private enum class NotificationSwitchOutcome {
        Connected,
        Failed,
        Superseded,
        TimedOut
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            if (network != currentNetwork) return

            networkLossJob?.cancel()
            networkLossJob = scope.launch {
                delay(NETWORK_LOSS_GRACE_MS)
                if (network != currentNetwork) return@launch

                val upstream = findActiveUpstreamNetwork()
                if (upstream != null) {
                    handleNetworkChange(upstream, "Fallback")
                    return@launch
                }

                if (OlcboxVpnState.status.value is VpnStatus.Connected ||
                    OlcboxVpnState.status.value is VpnStatus.Reconnecting
                ) {
                    updateUnderlyingNetwork(null)
                    unbindProcessFromNetwork()
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification("Waiting for network...")
                    addLog("Waiting for upstream network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.isUsableUpstream()) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return
            networkLossJob?.cancel()

            val upstream = findActiveUpstreamNetwork() ?: return
            if (currentNetwork == upstream) {
                if (OlcboxVpnState.status.value is VpnStatus.Reconnecting &&
                    startupJob?.isActive != true
                ) {
                    addLog("Network $reason: ${getNetName(upstream)}")
                    requestTransportRecovery(
                        reason = "Network available",
                        fullRestart = false,
                        delayMs = NETWORK_STABILITY_GRACE_MS
                    )
                }
                return
            }

            val previousTransport = currentNetworkTransport
            val nextTransport = upstream.transportOrNull()
            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            when (OlcboxVpnState.status.value) {
                is VpnStatus.Connected -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Keeping transport on refreshed Wi-Fi network")
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS,
                            setReconnectingImmediately = false
                        )
                    }
                }

                is VpnStatus.Reconnecting -> {
                    val candidateGeneration = generation
                    val candidateProfileId = activeProfileStorageId
                    if (isBenignWifiRefresh(previousTransport, nextTransport) &&
                        olcRtcRuntime.isRunning &&
                        connectedTunnelGeneration == candidateGeneration &&
                        canReconnectTransportInPlace()
                    ) {
                        if (setConnectedForGeneration(
                                requestedGeneration = candidateGeneration,
                                connectedProfileId = candidateProfileId
                            )
                        ) {
                            updateNotification(connectedNotificationText())
                            startWatchdog()
                        }
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS
                        )
                    }
                }

                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olcbox::VpnWakeLock")
            .apply { setReferenceCounted(false) }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lifecycleTransitionLock) {
            latestServiceStartId = startId
        }
        if (isProtectedNotificationAction(intent?.action) && isDeviceLocked()) {
            addLog("Ignored VPN notification control while device is locked")
            finishRejectedServiceStart(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            OlcboxVpnActions.ACTION_STOP_VPN -> {
                invalidateNotificationProfileInput()
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_SWITCH_PREVIOUS_PROFILE -> {
                if (!requestNotificationProfileStep(-1)) {
                    finishRejectedServiceStart(startId)
                }
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_SWITCH_NEXT_PROFILE -> {
                if (!requestNotificationProfileStep(1)) {
                    finishRejectedServiceStart(startId)
                }
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_APPLY_SELECTED_PROFILE -> {
                if (!requestNotificationSelectedProfileSwitch(
                        intent.getStringExtra(OlcboxVpnActions.EXTRA_PROFILE_STORAGE_ID)
                    )
                ) {
                    finishRejectedServiceStart(startId)
                }
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_START_VPN -> {
                invalidateNotificationProfileInput()
            }
            else -> {
                notificationSwitchRequestId.incrementAndGet()
                cleanup()
                return START_NOT_STICKY
            }
        }

        applyStartOptions(loadStartOptions(intent))
        val isRestart = shouldRestartForStartCommand()
        if (isRestart) {
            addLog("Restarting ${activeModeLabel()} for selected location")
        }
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                "Starting proxy..."
            } else {
                "Protecting your connection"
            }
        )
        startTunnel(isMigration = false, isRestart = isRestart)
        return START_REDELIVER_INTENT
    }

    private fun finishRejectedServiceStart(startId: Int) {
        var stopImmediately = false
        var pendingCleanup: Job? = null
        var pendingCleanupGeneration = 0L
        synchronized(lifecycleTransitionLock) {
            if (latestServiceStartId != startId) return
            val unfinishedCleanup = cleanupJob?.takeIf { !it.isCompleted }
            when {
                OlcboxVpnState.status.value is VpnStatus.Disconnected &&
                    startupJob?.isCompleted != false &&
                    unfinishedCleanup == null &&
                    vpnInterface == null &&
                    tun2socksThread == null &&
                    socksProxy == null &&
                    externalEngine == null &&
                    tunSocksBridge == null &&
                    !mobileRuntimeStarted &&
                    !olcRtcRuntime.isRunning -> {
                    stopImmediately = true
                }
                lifecycleStopping && cleanupShouldStopService -> {
                    pendingCleanup = unfinishedCleanup
                    pendingCleanupGeneration = cleanupTargetGeneration
                }
            }
        }
        if (stopImmediately) {
            stopSelfResult(startId)
            return
        }
        val cleanupToAwait = pendingCleanup ?: return
        scope.launch {
            cleanupToAwait.join()
            val shouldStop = synchronized(lifecycleTransitionLock) {
                latestServiceStartId == startId &&
                    generation == pendingCleanupGeneration &&
                    lifecycleStopping &&
                    OlcboxVpnState.status.value is VpnStatus.Disconnected
            }
            if (shouldStop) stopSelfResult(startId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        olcRtcRuntime.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return this@OlcboxVpnService.protect(fd.toInt())
            }
        })
    }

    private fun loadStartOptions(intent: Intent): StartOptions {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        val socksPort = if (intent.hasExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT)) {
            intent.getIntExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.DEFAULT_PORT
            )
        } else {
            preferences?.get(KEY_ANDROID_SOCKS_PORT)
        }

        return StartOptions(
            connectionMode = AndroidConnectionMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE)
                    ?: preferences?.get(KEY_ANDROID_CONNECTION_MODE)
            ),
            socksListenHost = AndroidSocksProxySettings.sanitizeHost(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_HOST)
            ),
            socksListenPort = AndroidSocksProxySettings.sanitizePort(socksPort),
            socksUsername = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_USERNAME)
                ).orEmpty().takeIf { it.isNotBlank() }.orEmpty(),
            socksPassword = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_PASSWORD)
                ).orEmpty(),
            splitTunnelProfiles = resolveAndroidSplitTunnelProfiles(
                preferences = preferences,
                stringExtra = intent::getStringExtra,
                packagesExtra = { intent.stringCollectionExtra(it) }
            )
        )
    }

    private fun applyStartOptions(options: StartOptions) {
        connectionMode = options.connectionMode
        socksListenHost = options.socksListenHost
        socksListenPort = options.socksListenPort
        socksUsername = options.socksUsername
        socksPassword = options.socksPassword
        splitTunnelProfiles = options.splitTunnelProfiles
    }

    private fun applySplitTunnelSettings(settings: AndroidSplitTunnelSettings) {
        splitTunnelMode = settings.mode
        splitTunnelProxyApps = settings.proxyPackages
        splitTunnelBypassApps = settings.bypassPackages
    }

    private fun Intent.stringCollectionExtra(key: String): Set<String>? {
        @Suppress("DEPRECATION")
        val value = extras?.get(key) ?: return null
        val items = when (value) {
            is ArrayList<*> -> value.asSequence()
            is Set<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return emptySet()
        }
        return items
            .mapNotNull { (it as? String)?.trim()?.takeIf { item -> item.isNotBlank() } }
            .toSet()
    }

    private fun startTunnel(
        isMigration: Boolean,
        forceFullRestart: Boolean = false,
        isRestart: Boolean = false,
        expectedGeneration: Long? = null,
        expectedNotificationRequestId: Long? = null,
        onGenerationClaimed: ((Long) -> Unit)? = null
    ): Long? {
        var claimedStartupJob: Job? = null
        val requestedGeneration = synchronized(lifecycleTransitionLock) {
            if (expectedGeneration != null &&
                (generation != expectedGeneration || lifecycleStopping)
            ) {
                return@synchronized null
            }
            if (expectedNotificationRequestId != null &&
                !isCurrentNotificationSwitch(expectedNotificationRequestId)
            ) {
                return@synchronized null
            }
            if (expectedGeneration == null && expectedNotificationRequestId == null) {
                lifecycleStopping = false
            }

            val previousStartupJob = startupJob
            val previousCleanupJob = cleanupJob
            previousStartupJob?.cancel()
            watchdogJob?.cancel()
            networkLossJob?.cancel()
            recoveryJob?.cancel()
            recoveryJob = null
            if (previousStartupJob?.isActive == true) {
                addLog("Canceling pending VPN start")
            }
            if (!isMigration) {
                resetRecoveryState()
            }
            val claimedGeneration = ++generation
            onGenerationClaimed?.invoke(claimedGeneration)
            refreshWakeLock(force = true)

            val newStartupJob = scope.launch(start = CoroutineStart.LAZY) {
                val requestedGeneration = claimedGeneration
            try {
                if (previousStartupJob?.isActive == true) {
                    addLog("Stopping previous olcRTC start")
                    stopMobile()
                }
                if (!awaitPreviousLifecycleJob(previousStartupJob, "VPN start") ||
                    !awaitPreviousLifecycleJob(previousCleanupJob, "VPN cleanup")
                ) {
                    if (requestedGeneration == generation) {
                        val message = "Previous VPN operation is still stopping"
                        setStatus(VpnStatus.Error(message))
                        updateNotification("Tunnel restart failed")
                    }
                    return@launch
                }

                if (!isMigration) {
                    registerNetworkMonitor()
                    updateUnderlyingNetwork(findActiveUpstreamNetwork())
                }

                tunnelMutex.withLock {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) return@withLock

                    val bundle = profileSelectionMutex.withLock { repository.getBundle() }
                    val active = bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
                    notificationProfileCount = bundle.locations.count { it.isComplete() }
                    if (active == null || !active.isComplete()) {
                        activeProfileName = ""
                        setStatus(VpnStatus.Error("No active VPN profile"))
                        updateNotification("Add a VPN profile first")
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }

                    val profile = active.profile
                    if (activeProfileStorageId != active.storageId) {
                        sessionConnectedAtElapsedRealtimeMs = null
                    }
                    activeProfileName = notificationSafeProfileName(active.displayName())
                    activeProfileStorageId = active.storageId
                    activeProfileType = profile.normalizedType
                    updateNotification(lastNotificationStatus)
                    if (profile.isOlcRtc() && !active.location.hasValidCryptoKey()) {
                        val message = "olcRTC key must be 64 hexadecimal characters"
                        addLog(message)
                        setStatus(VpnStatus.Error(message))
                        updateNotification("Fix the olcRTC profile key")
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }
                    val refreshedSplitTunnelProfiles = if (expectedNotificationRequestId != null) {
                        loadAndroidSplitTunnelProfiles(applicationContext.vpnPrefDataStore.data)
                    } else {
                        null
                    }
                    coroutineContext.ensureActive()
                    synchronized(lifecycleTransitionLock) {
                        if (requestedGeneration != generation || lifecycleStopping ||
                            (expectedNotificationRequestId != null &&
                                !isCurrentNotificationSwitch(expectedNotificationRequestId))
                        ) {
                            return@withLock
                        }
                        if (refreshedSplitTunnelProfiles != null) {
                            splitTunnelProfiles = refreshedSplitTunnelProfiles
                        }
                        applySplitTunnelSettings(
                            splitTunnelProfiles[AndroidSplitTunnelProfile.fromProfileType(profile.normalizedType)]
                        )
                    }
                    if (!profile.isOlcRtc()) {
                        startExternalProfile(
                            profile = profile,
                            requestedGeneration = requestedGeneration,
                            isRestart = isRestart,
                            profileStorageId = active.storageId
                        )
                        return@withLock
                    }

                    activeProfileType = VpnProfileConfig.TYPE_OLCRTC
                    val location = active.location.normalized()
                    if (isMigration && !forceFullRestart && canReconnectTransportInPlace()) {
                        reconnectTransport(location, requestedGeneration, active.storageId)
                    } else {
                        startFullTunnel(
                            location,
                            requestedGeneration,
                            isMigration,
                            isRestart,
                            active.storageId
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestedGeneration == generation) {
                    val message = e.message ?: "VPN start failed"
                    addLog("VPN start failed safely: $message")
                    val stopped = cleanupUnexpectedTunnelStart(requestedGeneration)
                    if (requestedGeneration == generation) {
                        updateUnderlyingNetwork(null)
                        unbindProcessFromNetwork()
                        if (!stopped) addLog("Partial VPN start did not stop cleanly")
                        setStatus(VpnStatus.Error(message))
                        updateNotification("Connection failed")
                    }
                }
            } finally {
                if (claimedGeneration == generation) {
                    releaseWakeLock()
                }
            }
            }
            startupJob = newStartupJob
            claimedStartupJob = newStartupJob
            claimedGeneration
        } ?: return null
        claimedStartupJob?.start()
        return requestedGeneration
    }

    private suspend fun reconnectTransport(
        location: LocationConfig,
        requestedGeneration: Long,
        profileStorageId: String
    ) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification("Waiting for network...")
            addLog("No upstream network; keeping tunnel alive")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val startResult = startMobile(location, upstream, requestedGeneration)
        if (requestedGeneration != generation) return
        if (startResult.outcome == MobileStartOutcome.Ready) {
            if (!setConnectedForGeneration(requestedGeneration, profileStorageId)) return
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected")
            startWatchdog()
            return
        }

        handleOlcRtcStartFailure(startResult, requestedGeneration)
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean,
        profileStorageId: String
    ) {
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Connecting...")
        if (!stopTransportProcesses(closeTun = true, waitForSocksPort = true)) {
            setStatus(VpnStatus.Error("Previous VPN tunnel is still stopping"))
            updateNotification("Tunnel restart failed")
            return
        }
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for network...")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }
        updateUnderlyingNetwork(upstream)

        val startResult = startMobile(location, upstream, requestedGeneration)
        if (requestedGeneration != generation) return
        if (startResult.outcome != MobileStartOutcome.Ready) {
            handleOlcRtcStartFailure(startResult, requestedGeneration)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) {
            stopSupersededTunnelStart()
            return
        }

        if (connectionMode == AndroidConnectionMode.Proxy) {
//            if (!startAuthenticatedSocksProxy()) {
//                stopTransportProcesses(closeTun = true)
//                return
//            }
            if (!setConnectedForGeneration(requestedGeneration, profileStorageId)) return
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("Proxy mode connected on SOCKS $socksListenHost:$socksListenPort")
            startWatchdog()
            return
        }

        if (!startTunSocksBridge()) {
            stopTransportProcesses(closeTun = true, waitForSocksPort = true)
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopTransportProcesses(closeTun = true, waitForSocksPort = true)
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) {
            stopSupersededTunnelStart()
            return
        }

        if (!setConnectedForGeneration(requestedGeneration, profileStorageId)) return
        resetRecoveryState()
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established")
        startWatchdog()
    }

    private suspend fun handleOlcRtcStartFailure(
        result: MobileStartResult,
        requestedGeneration: Long
    ) {
        if (requestedGeneration != generation || result.outcome == MobileStartOutcome.Superseded) return

        updateUnderlyingNetwork(null)
        if (result.outcome == MobileStartOutcome.RetryableFailure &&
            olcRtcStartRetryPolicy.shouldRetry(result.rawMessage, olcRtcStartRetryCount)
        ) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
            scheduleOlcRtcStartRetry(requestedGeneration)
            return
        }

        if (result.outcome == MobileStartOutcome.RetryableFailure) {
            addLog("olcRTC start retries exhausted")
        }
        val stopped = stopTransportProcesses(closeTun = true, waitForSocksPort = true)
        if (requestedGeneration == generation) {
            if (!stopped) addLog("Failed olcRTC tunnel did not stop cleanly")
            setStatus(VpnStatus.Error(result.message.ifBlank { "olcRTC start failed" }))
            updateNotification("Connection failed")
        }
    }

    private fun requestNotificationProfileStep(step: Int): Boolean {
        synchronized(lifecycleTransitionLock) {
            if (!canAcceptNotificationProfileSwitch()) return false
            synchronized(notificationProfileInputLock) {
                notificationProfileStepAccumulator.updateAndGet { pending ->
                    (pending + step.toLong()).coerceIn(
                        Int.MIN_VALUE.toLong(),
                        Int.MAX_VALUE.toLong()
                    )
                }
                val requestId = notificationSwitchRequestId.incrementAndGet()
                notificationProfileDebounceJob?.cancel()
                notificationProfileDebounceJob = scope.launch {
                    delay(NOTIFICATION_PROFILE_DEBOUNCE_MS)
                    val accumulatedSteps = synchronized(notificationProfileInputLock) {
                        if (!isCurrentNotificationSwitch(requestId)) return@launch
                        notificationProfileDebounceJob = null
                        notificationProfileStepAccumulator.get().toInt()
                    }
                    if (accumulatedSteps == 0) {
                        updateNotification(lastNotificationStatus)
                        return@launch
                    }
                    performNotificationProfileSwitch(accumulatedSteps, requestId)
                }
            }
        }
        return true
    }

    private fun requestNotificationSelectedProfileSwitch(targetProfileId: String?): Boolean {
        val sanitizedTargetId = targetProfileId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        val requestId = synchronized(lifecycleTransitionLock) {
            if (!canAcceptNotificationProfileSwitch()) return false
            synchronized(notificationProfileInputLock) {
                notificationProfileStepAccumulator.set(0L)
                notificationProfileDebounceJob?.cancel()
                notificationProfileDebounceJob = null
                notificationSwitchRequestId.incrementAndGet()
            }
        }
        scope.launch {
            performNotificationProfileSwitch(
                steps = null,
                requestId = requestId,
                selectedTargetId = sanitizedTargetId
            )
        }
        return true
    }

    private fun canAcceptNotificationProfileSwitch(): Boolean {
        if (!notificationForegroundStarted ||
            OlcboxVpnState.status.value is VpnStatus.Disconnected ||
            OlcboxVpnState.status.value is VpnStatus.Stopping
        ) {
            addLog("Ignored profile switch because VPN is not active")
            return false
        }
        return true
    }

    private suspend fun performNotificationProfileSwitch(
        steps: Int?,
        requestId: Long,
        selectedTargetId: String? = null
    ) {
        notificationProfileSwitchMutex.withLock {
            var previousProfileId: String? = null
            var targetId: String? = null
            try {
                if (!isCurrentNotificationSwitch(requestId)) return@withLock

                val bundle = repository.getBundle()
                val selectableProfiles = bundle.locations.filter { it.isComplete() }
                notificationProfileCount = selectableProfiles.size
                val orderedProfileIds = selectableProfiles.map { it.storageId }
                targetId = if (steps == null) {
                    selectedTargetId?.takeIf {
                        it in orderedProfileIds && it != activeProfileStorageId
                    }
                } else {
                    notificationProfileTargetId(
                        orderedProfileIds = orderedProfileIds,
                        activeProfileId = bundle.activeLocationId,
                        steps = steps
                    )
                }
                if (targetId == null) {
                    if (steps != null) commitNotificationProfileSteps(requestId, steps)
                    updateNotification(lastNotificationStatus)
                    return@withLock
                }

                val target = selectableProfiles.firstOrNull { it.storageId == targetId }
                    ?: return@withLock
                previousProfileId = lastConnectedProfileStorageId
                    ?.takeIf { it != targetId && it in orderedProfileIds }
                if (!isCurrentNotificationSwitch(requestId)) return@withLock

                val targetGeneration = profileSelectionMutex.withLock selection@{
                    if (!isCurrentNotificationSwitch(requestId)) return@selection null
                    val latestBundle = repository.getBundle()
                    val latestTarget = latestBundle.locations.firstOrNull {
                        it.storageId == target.storageId && it.isComplete()
                    } ?: return@selection null
                    if (steps != null && !commitNotificationProfileSteps(requestId, steps)) {
                        return@selection null
                    }

                    repository.setActiveLocationId(latestTarget.storageId)
                    startTunnel(
                        isMigration = false,
                        isRestart = true,
                        expectedNotificationRequestId = requestId,
                        onGenerationClaimed = {
                            sessionConnectedAtElapsedRealtimeMs = null
                            activeProfileName = notificationSafeProfileName(latestTarget.displayName())
                            activeProfileStorageId = latestTarget.storageId
                            activeProfileType = latestTarget.profile.normalizedType
                            setStatus(VpnStatus.Reconnecting)
                            updateNotification("Switching profile...")
                            addLog(
                                "Switching to ${latestTarget.profile.typeLabel()} from notification"
                            )
                        }
                    )
                } ?: return@withLock

                when (awaitNotificationProfileSwitch(
                    targetId = target.storageId,
                    requestId = requestId,
                    minimumConnectedGeneration = targetGeneration
                )) {
                    NotificationSwitchOutcome.Connected -> {
                        addLog("Notification profile switch connected")
                    }

                    NotificationSwitchOutcome.Superseded -> Unit
                    NotificationSwitchOutcome.Failed,
                    NotificationSwitchOutcome.TimedOut -> restorePreviousNotificationProfile(
                        previousProfileId = previousProfileId,
                        failedTargetId = target.storageId,
                        requestId = requestId
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("Notification profile switch failed safely: ${e.message}")
                if (isCurrentNotificationSwitch(requestId)) {
                    restorePreviousNotificationProfile(
                        previousProfileId = previousProfileId,
                        failedTargetId = targetId,
                        requestId = requestId
                    )
                }
            }
        }
    }

    private suspend fun awaitNotificationProfileSwitch(
        targetId: String,
        requestId: Long,
        minimumConnectedGeneration: Long
    ): NotificationSwitchOutcome {
        return withTimeoutOrNull(NOTIFICATION_PROFILE_SWITCH_TIMEOUT_MS) {
            while (coroutineContext.isActive) {
                if (!isCurrentNotificationSwitch(requestId)) {
                    return@withTimeoutOrNull NotificationSwitchOutcome.Superseded
                }
                when (OlcboxVpnState.status.value) {
                    VpnStatus.Connected -> if (
                        activeProfileStorageId == targetId &&
                        lastConnectedProfileStorageId == targetId &&
                        connectedTunnelGeneration >= minimumConnectedGeneration
                    ) {
                        return@withTimeoutOrNull NotificationSwitchOutcome.Connected
                    }

                    is VpnStatus.Error -> {
                        return@withTimeoutOrNull NotificationSwitchOutcome.Failed
                    }

                    VpnStatus.Disconnected,
                    VpnStatus.Stopping -> {
                        return@withTimeoutOrNull NotificationSwitchOutcome.Superseded
                    }

                    VpnStatus.Connecting,
                    VpnStatus.Reconnecting -> Unit
                }
                delay(NOTIFICATION_PROFILE_SWITCH_POLL_MS)
            }
            NotificationSwitchOutcome.Superseded
        } ?: NotificationSwitchOutcome.TimedOut
    }

    private fun commitNotificationProfileSteps(requestId: Long, steps: Int): Boolean {
        return synchronized(notificationProfileInputLock) {
            if (requestId != notificationSwitchRequestId.get()) return@synchronized false
            notificationProfileStepAccumulator.compareAndSet(steps.toLong(), 0L)
        }
    }

    private fun invalidateNotificationProfileInput() {
        synchronized(notificationProfileInputLock) {
            notificationSwitchRequestId.incrementAndGet()
            notificationProfileStepAccumulator.set(0L)
            notificationProfileDebounceJob?.cancel()
            notificationProfileDebounceJob = null
        }
    }

    private suspend fun restorePreviousNotificationProfile(
        previousProfileId: String?,
        failedTargetId: String?,
        requestId: Long
    ) {
        if (!isCurrentNotificationSwitch(requestId)) return
        val fallbackId = previousProfileId?.takeIf { it != failedTargetId }
        if (fallbackId == null) {
            updateNotification("Profile switch failed")
            return
        }

        profileSelectionMutex.withLock {
            if (!isCurrentNotificationSwitch(requestId)) return
            val bundle = repository.getBundle()
            val fallback = bundle.locations.firstOrNull {
                it.storageId == fallbackId && it.isComplete()
            }
            if (fallback == null) {
                updateNotification("Profile switch failed")
                return
            }

            repository.setActiveLocationId(fallbackId)
            startTunnel(
                isMigration = false,
                isRestart = true,
                expectedNotificationRequestId = requestId,
                onGenerationClaimed = {
                    sessionConnectedAtElapsedRealtimeMs = null
                    activeProfileName = notificationSafeProfileName(fallback.displayName())
                    activeProfileStorageId = fallback.storageId
                    activeProfileType = fallback.profile.normalizedType
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification("Restoring previous profile...")
                    addLog(
                        "Restoring previous ${fallback.profile.typeLabel()} profile after failed switch"
                    )
                }
            )
        }
    }

    private fun isCurrentNotificationSwitch(requestId: Long): Boolean {
        return requestId == notificationSwitchRequestId.get() &&
            notificationForegroundStarted &&
            OlcboxVpnState.status.value !is VpnStatus.Disconnected &&
            OlcboxVpnState.status.value !is VpnStatus.Stopping
    }

    private fun isProtectedNotificationAction(action: String?): Boolean = when (action) {
        OlcboxVpnActions.ACTION_STOP_VPN,
        OlcboxVpnActions.ACTION_SWITCH_PREVIOUS_PROFILE,
        OlcboxVpnActions.ACTION_SWITCH_NEXT_PROFILE,
        OlcboxVpnActions.ACTION_APPLY_SELECTED_PROFILE -> true
        else -> false
    }

    private fun isDeviceLocked(): Boolean {
        return (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    }

    private suspend fun startTunSocksBridge(): Boolean {
        if (!requiresTunSocksBridge()) return true

        val bridge = createTunSocksBridge(
            context = applicationContext,
            profileType = activeProfileType,
            upstreamSocksHost = socksConnectHost(),
            upstreamSocksPort = socksListenPort,
            bridgeSocksPort = allocateLocalSocksPort(),
            username = socksUsername,
            password = socksPassword,
            log = ::addLog
        )
        tunSocksBridge = bridge

        return try {
            bridge.start()
            true
        } catch (e: CancellationException) {
            stopTunSocksBridge()
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "TUN DNS bridge start failed"
            addLog("TUN DNS bridge start failed: $message")
            setStatus(VpnStatus.Error(message))
            updateNotification("Tunnel failed")
            stopTunSocksBridge()
            false
        }
    }

    private suspend fun awaitPreviousLifecycleJob(previousJob: Job?, operation: String): Boolean {
        if (previousJob == null || previousJob.isCompleted) return true

        addLog("Waiting for previous $operation to stop")
        val completed = withTimeoutOrNull(PREVIOUS_LIFECYCLE_WAIT_MS) {
            previousJob.join()
            true
        } ?: false
        if (!completed) {
            previousJob.cancel()
            addLog("Previous $operation did not stop within the safety timeout")
        }
        return completed
    }

    private suspend fun cleanupUnexpectedTunnelStart(requestedGeneration: Long): Boolean {
        return withContext(NonCancellable) {
            try {
                withTimeoutOrNull(FAILED_START_CLEANUP_WAIT_MS) {
                    tunnelMutex.withLock {
                        if (requestedGeneration != generation) return@withLock true
                        stopTransportProcesses(closeTun = true, waitForSocksPort = true)
                    }
                } ?: false
            } catch (cleanupFailure: Exception) {
                addLog("Partial VPN start cleanup failed: ${cleanupFailure.message}")
                false
            }
        }
    }

    private suspend fun startExternalProfile(
        profile: VpnProfileConfig,
        requestedGeneration: Long,
        isRestart: Boolean,
        profileStorageId: String
    ) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Starting ${profile.typeLabel()}...")
        addLog("Starting ${profile.typeLabel()} profile")

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for network...")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        if (!stopTransportProcesses(
                closeTun = true,
                waitForSocksPort = true
            )
        ) {
            setStatus(VpnStatus.Error("Previous VPN tunnel is still stopping"))
            updateNotification("Tunnel restart failed")
            return
        }
        bindProcessToNetwork(
            upstream,
            "Bound ${profile.typeLabel()} transport to ${getNetName(upstream)}"
        )
        activeProfileType = profile.normalizedType

        val existingSocksPort = existingProfileSocksPort(profile)
        val targetSocksPort = existingSocksPort ?: allocateLocalSocksPort()
        if (existingSocksPort == null) {
            socksUsername = socksUsername.ifBlank { INTERNAL_SOCKS_USERNAME }
            socksPassword = socksPassword.ifBlank { INTERNAL_SOCKS_PASSWORD }
        }

        val engine = createSocksBackedVpnEngine(
            context = applicationContext,
            profile = profile,
            defaultSocksPort = targetSocksPort,
            username = socksUsername,
            password = socksPassword,
            log = ::addLog
        )
        externalEngine = engine
        socksListenHost = engine.socksHost
        socksListenPort = engine.socksPort

        try {
            engine.start()
        } catch (e: CancellationException) {
            stopExternalEngine()
            unbindProcessFromNetwork()
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "${profile.typeLabel()} start failed"
            addLog("${profile.typeLabel()} start failed: $message")
            setStatus(VpnStatus.Error(message))
            updateNotification("Connection failed")
            stopExternalEngine()
            unbindProcessFromNetwork()
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) {
            stopSupersededTunnelStart()
            return
        }

        if (connectionMode == AndroidConnectionMode.Proxy) {
            if (!setConnectedForGeneration(requestedGeneration, profileStorageId)) return
            resetRecoveryState()
            updateNotification("${profile.typeLabel()} SOCKS connected")
            addLog("${profile.typeLabel()} SOCKS connected on $socksListenHost:$socksListenPort")
            startWatchdog()
            return
        }

        if (!startTunSocksBridge()) {
            stopTransportProcesses(closeTun = true, waitForSocksPort = false)
            return
        }

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopTransportProcesses(closeTun = true, waitForSocksPort = false)
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true, waitForSocksPort = false)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) {
            stopSupersededTunnelStart()
            return
        }

        if (!setConnectedForGeneration(requestedGeneration, profileStorageId)) return
        resetRecoveryState()
        updateNotification("${profile.typeLabel()} connected")
        addLog("${profile.typeLabel()} VPN tunnel established")
        startWatchdog()
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long
    ): MobileStartResult {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            val targetSocksPort = socksListenPort
            val deviceId = deviceIdentityProvider.hwid()
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            if (!waitForMobileRuntimeStopped(MOBILE_RUNTIME_IDLE_QUICK_TIMEOUT_MS)) {
                throw IllegalStateException("Previous olcRTC runtime is still stopping")
            }
            waitForJitsiRoomCleanup(config.bypassProvider)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, ${diagnosticOlcRtcRoomReference(config.id)}"
            )
            lastMobileProvider = config.bypassProvider
            synchronized(mobileRuntimeCallLock) {
                if (requestedGeneration != generation) {
                    throw CancellationException("olcRTC start superseded")
                }
                coroutineContext.ensureActive()
                // gomobile Runtime configuration and stop must never overlap.
                installMobileCallbacks()
                configureMobileRuntime(config, deviceId, targetSocksPort)
                olcRtcRuntime.start()
                mobileRuntimeStarted = true
            }
            olcRtcRuntime.waitReady(MOBILE_READY_TIMEOUT_MS)
            if (requestedGeneration != generation) {
                throw CancellationException("olcRTC start superseded")
            }
            coroutineContext.ensureActive()
            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            markRtcConnected()
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            MobileStartResult(MobileStartOutcome.Ready)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val rawMessage = e.message ?: "Transport failed"
            val message = sanitizeOlcRtcDiagnosticOutput(
                friendlyOlcRtcFailure(rawMessage),
                config.id
            )
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            val stopped = stopMobileAndWait()
            if (staleRequest) {
                MobileStartResult(MobileStartOutcome.Superseded, message, rawMessage)
            } else if (!stopped) {
                MobileStartResult(
                    MobileStartOutcome.RetryableFailure,
                    "Previous olcRTC runtime is still stopping",
                    "Previous olcRTC runtime is still stopping"
                )
            } else if (isRetryableOlcRtcStartFailure(rawMessage)) {
                MobileStartResult(MobileStartOutcome.RetryableFailure, message, rawMessage)
            } else {
                MobileStartResult(MobileStartOutcome.FatalFailure, message, rawMessage)
            }
        } finally {
            if (!keepProcessBound || !olcRtcRuntime.isRunning) {
                unbindProcessFromNetwork()
            }
        }
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileRuntime(
        location: LocationConfig,
        deviceId: String,
        socksPort: Int
    ) {
        val config = location.normalized()
        olcRtcRuntime.setProvider(config.bypassProvider)
        olcRtcRuntime.setTransport(config.transport)
        olcRtcRuntime.setRoom(config.id)
        olcRtcRuntime.setKey(config.key)
        olcRtcRuntime.setDeviceID(deviceId)
        olcRtcRuntime.setDNS(resolveOlcRtcDnsServer(config.dnsServer))
        olcRtcRuntime.setSocksListenHost(socksListenHost)
        olcRtcRuntime.setSocksPort(socksPort.toLong())
        olcRtcRuntime.setSocksCredentials(socksUsername, socksPassword)
        olcRtcRuntime.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return try {
            if (
                tun2socksThread?.isAlive == true ||
                tun2socksStarted ||
                isNativeTun2socksActive()
            ) {
                addLog("Refusing to start a second tun2socks instance")
                setStatus(VpnStatus.Error("Previous VPN tunnel is still stopping"))
                updateNotification("Tunnel restart failed")
                return false
            }
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification("Tunnel failed")
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig()
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "OlcboxTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (OlcboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification("Tunnel failed")
            false
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Unified VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS_SERVER)
                .setBlocking(true)

            if (!applySplitTunneling(builder)) return null

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
                ?: throw IllegalStateException(
                    "VPN permission is not prepared or was revoked; reconnect from the app"
                )
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun applySplitTunneling(builder: Builder): Boolean {
        return when (splitTunnelMode) {
            AndroidSplitTunnelMode.AllApps -> {
                addDisallowedApp(builder, packageName, "Unified VPN")
                addLog("Split tunneling: all apps use TUN")
                true
            }

            AndroidSplitTunnelMode.ProxySelected -> {
                val packages = splitTunnelProxyApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()

                if (packages.isEmpty()) {
                    addLog("Split tunneling proxy list is empty")
                    setStatus(VpnStatus.Error("Select apps for split tunneling"))
                    updateNotification("Split tunneling error")
                    return false
                }

                val applied = packages.count { addAllowedApp(builder, it) }
                if (applied == 0) {
                    addLog("Split tunneling has no valid proxy apps")
                    setStatus(VpnStatus.Error("Selected apps are unavailable"))
                    updateNotification("Split tunneling error")
                    false
                } else {
                    addLog("Split tunneling: $applied selected apps use TUN")
                    true
                }
            }

            AndroidSplitTunnelMode.BypassSelected -> {
                addDisallowedApp(builder, packageName, "Unified VPN")
                val applied = splitTunnelBypassApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .count { addDisallowedApp(builder, it) }

                if (applied == 0) {
                    addLog("Split tunneling: no selected apps bypass TUN")
                } else {
                    addLog("Split tunneling: $applied selected apps bypass TUN")
                }
                true
            }
        }
    }

    private fun addAllowedApp(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to route $targetPackage through TUN: ${it.message}")
            false
        }
    }

    private fun addDisallowedApp(
        builder: Builder,
        targetPackage: String,
        label: String = targetPackage
    ): Boolean {
        return runCatching {
            builder.addDisallowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to bypass $label from TUN: ${it.message}")
            false
        }
    }

    private fun writeTun2socksConfig(): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)

        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS

            socks5:
              address: ${tun2SocksHost()}
              port: ${tun2SocksPort()}
              # Standard SOCKS5 UDP ASSOCIATE is supported by sing-box and Xray.
              # The tcp mode uses hev's private command 5, which these cores reject.
              udp: 'udp'
              pipeline: false
              username: '$socksUsername'
              password: '$socksPassword'

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        watchdogJob = scope.launch {
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)
                when {
                    activeProfileType == VpnProfileConfig.TYPE_OLCRTC && !olcRtcRuntime.isRunning -> {
                        addLog("Watchdog: olcRTC stopped")
                        requestTransportRecovery("olcRTC stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun &&
                        requiresTunSocksBridge() &&
                        tunSocksBridge?.isRunning != true -> {
                        addLog("Watchdog: TUN DNS bridge stopped")
                        requestTransportRecovery("TUN DNS bridge stopped", fullRestart = true)
                        return@launch
                    }

                    activeProfileType != VpnProfileConfig.TYPE_OLCRTC &&
                        externalEngine?.isRunning != true -> {
                        addLog("Watchdog: ${activeModeLabel()} core stopped")
                        requestTransportRecovery("${activeModeLabel()} core stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && !isLocalSocksPortOpen(socksListenPort) -> {
                        addLog("Watchdog: SOCKS port is not accepting connections")
                        requestTransportRecovery("SOCKS port unavailable", fullRestart = true)
                        return@launch
                    }
                }

                val upstream = findActiveUpstreamNetwork()
                if (upstream == null) {
                    addLog("Watchdog: no upstream network")
                    requestTransportRecovery("No upstream network", fullRestart = false)
                    return@launch
                }

                if (currentNetwork != upstream) {
                    val previousTransport = currentNetworkTransport
                    val nextTransport = upstream.transportOrNull()
                    updateUnderlyingNetwork(upstream)
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Watchdog: refreshed Wi-Fi upstream")
                        continue
                    }
                    addLog("Watchdog: upstream changed to ${getNetName(upstream)}")
                    requestTransportRecovery("Upstream network changed", fullRestart = false)
                    return@launch
                }

                if (mode == AndroidConnectionMode.Tun && isTunTrafficStalled()) {
                    addLog("Watchdog: TUN traffic has no upstream response")
                    requestTransportRecovery("TUN traffic stalled", fullRestart = false)
                    return@launch
                }
            }
        }
    }

    private fun cleanup(stopService: Boolean = true) {
        var claimedCleanupJob: Job? = null
        var stopIdleService = false
        var idleStopStartId = 0
        synchronized(lifecycleTransitionLock) {
            val activeCleanupJob = cleanupJob?.takeIf { !it.isCompleted }
            if (lifecycleStopping &&
                activeCleanupJob != null &&
                cleanupTargetGeneration == generation
            ) {
                cleanupShouldStopService = cleanupShouldStopService || stopService
                if (stopService) cleanupStopStartId = latestServiceStartId
                return@synchronized
            }

            invalidateNotificationProfileInput()

            val status = OlcboxVpnState.status.value
            if (status is VpnStatus.Disconnected &&
                startupJob?.isCompleted != false &&
                vpnInterface == null &&
                tun2socksThread == null &&
                socksProxy == null &&
                externalEngine == null &&
                tunSocksBridge == null &&
                !mobileRuntimeStarted &&
                !olcRtcRuntime.isRunning &&
                activeCleanupJob == null
            ) {
                stopIdleService = stopService
                if (stopService) idleStopStartId = latestServiceStartId
                return@synchronized
            }

            lifecycleStopping = true
            val cleanupGeneration = ++generation
            cleanupTargetGeneration = cleanupGeneration
            cleanupShouldStopService = stopService
            cleanupStopStartId = latestServiceStartId
            val stoppingModeLabel = activeModeLabel()
            setStatus(VpnStatus.Stopping)
            startupJob?.cancel()
            watchdogJob?.cancel()
            networkLossJob?.cancel()
            recoveryJob?.cancel()
            recoveryJob = null
            releaseWakeLock()

            if (isCallbackRegistered) {
                runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
                isCallbackRegistered = false
            }
            val newCleanupJob = scope.launch(start = CoroutineStart.LAZY) {
                var transportsStopped = false
                try {
                    tunnelMutex.withLock {
                        transportsStopped = stopVisibleVpnProcesses()
                        transportsStopped = stopMobileAndWait() && transportsStopped
                        resetRecoveryState()
                        updateUnderlyingNetwork(null)
                        unbindProcessFromNetwork()
                    }
                } finally {
                    var shouldStopService = false
                    var stopStartId = 0
                    synchronized(lifecycleTransitionLock) {
                        if (generation == cleanupGeneration &&
                            lifecycleStopping &&
                            cleanupTargetGeneration == cleanupGeneration
                        ) {
                            if (transportsStopped) {
                                setStatus(VpnStatus.Disconnected, allowWhileStopping = true)
                                addLog("$stoppingModeLabel stopped")
                            } else {
                                setStatus(
                                    VpnStatus.Error("VPN tunnel did not stop cleanly"),
                                    allowWhileStopping = true
                                )
                                updateNotification("Tunnel is still stopping")
                            }
                            shouldStopService = cleanupShouldStopService && transportsStopped
                            stopStartId = cleanupStopStartId
                        }
                    }
                    if (shouldStopService) stopSelfResult(stopStartId)
                }
            }
            cleanupJob = newCleanupJob
            claimedCleanupJob = newCleanupJob
        }
        claimedCleanupJob?.start()
        if (stopIdleService) stopSelfResult(idleStopStartId)
    }

    private suspend fun stopVisibleVpnProcesses(): Boolean {
        OlcboxVpnState.clearConnectedProxy()
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        val externalStopped = stopExternalEngine()
        stopTun2socks()
        cleanupVpnInterface()
        tunThread?.interrupt()
        val tunStopped = waitForTun2socksStopped(tunThread)
        if (tunStopped && tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        stopTunSocksBridge()
        unbindProcessFromNetwork()
        return externalStopped && tunStopped
    }

    private suspend fun waitForTun2socksStopped(thread: Thread?): Boolean {
        if (thread == null && !isNativeTun2socksActive()) return true
        val stopped = withTimeoutOrNull(TUN2SOCKS_STOP_WAIT_MS) {
            while (thread?.isAlive == true || isNativeTun2socksActive()) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } ?: false
        if (!stopped) {
            addLog("tun2socks did not stop within ${TUN2SOCKS_STOP_WAIT_MS / 1_000}s")
        }
        return stopped
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true
    ): Boolean {
        OlcboxVpnState.clearConnectedProxy()
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        val externalStopped = stopExternalEngine()
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tunThread?.interrupt()
        val tunStopped = !closeTun || waitForTun2socksStopped(tunThread)
        if (tunStopped && tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        stopTunSocksBridge()
        if (waitForSocksPort) {
            val mobileStopped = stopMobileAndWait()
            if (closeTun) {
                unbindProcessFromNetwork()
            }
            return externalStopped && tunStopped && mobileStopped
        } else {
            stopMobile()
        }
        if (closeTun) {
            unbindProcessFromNetwork()
        }
        return externalStopped && tunStopped
    }

    private suspend fun stopSupersededTunnelStart() {
        addLog("VPN start superseded; stopping partial tunnel")
        stopTransportProcesses(closeTun = true, waitForSocksPort = true)
    }

    private fun stopTun2socks() {
        if (
            nativeLibrariesLoaded &&
            (tun2socksStarted || isNativeTun2socksActive()) &&
            !tun2socksStopRequested
        ) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
                .onFailure { addLog("tun2socks stop request failed: ${it.message}") }
        }
    }

    private fun isNativeTun2socksActive(): Boolean {
        if (!nativeLibrariesLoaded) return false
        return runCatching { isTun2socksRunningNative() }.getOrDefault(false)
    }

    private fun stopMobile() {
        runCatching {
            synchronized(mobileRuntimeCallLock) {
                olcRtcRuntime.stop(MOBILE_STOP_TIMEOUT_MS)
            }
        }
            .onFailure { addLog("olcRTC stop request did not finish: ${it.message}") }
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private fun stopExternalEngine(): Boolean {
        val engine = externalEngine ?: return true
        return try {
            engine.stop()
            if (externalEngine === engine) externalEngine = null
            true
        } catch (failure: Exception) {
            addLog("External VPN core did not stop: ${failure.message}")
            false
        }
    }

    private fun stopTunSocksBridge() {
        tunSocksBridge?.stop()
        tunSocksBridge = null
    }

    private suspend fun stopMobileAndWait(): Boolean {
        val socksPort = socksListenPort
        val provider = lastMobileProvider
        val hadStartedRuntime = mobileRuntimeStarted || olcRtcRuntime.isRunning
        stopMobile()
        val runtimeStopped = waitForMobileRuntimeStopped(MOBILE_RUNTIME_STOP_WAIT_MS)
        var socksReleased = true
        if (runtimeStopped && hadStartedRuntime) {
            mobileRuntimeStarted = false
            if (provider == LocationConfig.PROVIDER_JITSI) {
                lastJitsiStopCompletedAtMs = System.currentTimeMillis()
            }
            socksReleased = waitForSocksPortReleased(socksPort)
        }
        return runtimeStopped && socksReleased
    }

    private suspend fun waitForMobileRuntimeStopped(timeoutMs: Long): Boolean {
        val stopped = withTimeoutOrNull(timeoutMs) {
            while (olcRtcRuntime.isRunning) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } ?: false
        if (!stopped) {
            addLog("olcRTC runtime is still stopping after ${timeoutMs / 1_000}s")
        }
        return stopped
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = socksListenPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return true
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
        return false
    }

    private fun allocateLocalSocksPort(): Int {
        for (port in LOCAL_SOCKS_PORT_BASE..LOCAL_SOCKS_PORT_MAX) {
            if (!isLocalSocksPortOpen(port, AndroidSocksProxySettings.DEFAULT_HOST)) return port
        }

        return ServerSocket(0).use { socket ->
            socket.localPort
        }
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return isLocalSocksPortOpen(port, socksConnectHost())
    }

    private fun resolveOlcRtcDnsServer(configuredDnsServer: String): String {
        val selectedDnsServer = selectOlcRtcDnsEndpoint(
            configuredValue = configuredDnsServer,
            fallbackValue = DEFAULT_OLCRTC_DNS_SERVER
        )
        val source = if (configuredDnsServer.isBlank()) "default" else "configured"
        addLog("Using $source DNS server $selectedDnsServer for olcRTC signaling")
        return selectedDnsServer
    }

    private fun isLocalSocksPortOpen(port: Int, host: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(host, port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun socksConnectHost(): String {
        return AndroidSocksProxySettings.connectHost(socksListenHost)
    }

    private fun tun2SocksHost(): String {
        return tunSocksBridge?.socksHost ?: socksConnectHost()
    }

    private fun tun2SocksPort(): Int {
        return tunSocksBridge?.socksPort ?: socksListenPort
    }

    private fun requiresTunSocksBridge(): Boolean {
        return profileRequiresTunSocksBridge(activeProfileType)
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            noteRtcFailure(
                reason = "RTC failed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_FAILED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(
                reason = "RTC closed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_CLOSED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(
                reason = "RTC network path is closed",
                fullRestart = false,
                threshold = RTC_IO_ERROR_RECOVERY_THRESHOLD
            )
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(
        reason: String,
        fullRestart: Boolean,
        threshold: Int
    ) {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= RTC_FAILURE_WINDOW_MS) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        if (rtcFailureCount >= threshold) {
            requestTransportRecovery(reason, fullRestart)
        }
    }

    private fun isTunTrafficStalled(): Boolean {
        val stats = readTun2SocksStats() ?: return false
        publishSessionSnapshot(stats)
        val previous = watchdogTunStats
        watchdogTunStats = stats

        if (previous == null) return false

        val txDelta = stats.txPackets - previous.txPackets
        val rxDelta = stats.rxPackets - previous.rxPackets
        val coreRunning = if (activeProfileType == VpnProfileConfig.TYPE_OLCRTC) {
            olcRtcRuntime.isRunning
        } else {
            externalEngine?.isRunning == true
        }
        val transportRunning = coreRunning &&
            (!requiresTunSocksBridge() || tunSocksBridge?.isRunning == true)

        if (txDelta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rxDelta <= 0L && transportRunning) {
            watchdogStalledSamples++
        } else if (rxDelta > 0L || txDelta <= 0L) {
            watchdogStalledSamples = 0
        }

        return watchdogStalledSamples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    private fun readTun2SocksStats(): Tun2SocksStats? {
        if (!nativeLibrariesLoaded || !tun2socksStarted) return null
        return runCatching {
            val values = getTun2socksStatsNative()
            if (values.size < 4) return null
            Tun2SocksStats(
                txPackets = values[0],
                txBytes = values[1],
                rxPackets = values[2],
                rxBytes = values[3]
            )
        }.getOrNull()
    }

    private fun requestTransportRecovery(
        reason: String,
        fullRestart: Boolean,
        delayMs: Long = 0L,
        setReconnectingImmediately: Boolean = true
    ) {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        val recoveryGeneration = generation
        if (delayMs <= 0L &&
            recoveryRequestedForGeneration == recoveryGeneration &&
            recoveryJob?.isActive == true
        ) {
            return
        }

        recoveryJob?.cancel()
        if (setReconnectingImmediately && status is VpnStatus.Connected) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Reconnecting...")
        }

        recoveryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (generation != recoveryGeneration) return@launch
            val currentStatus = OlcboxVpnState.status.value
            if (currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Reconnecting) {
                return@launch
            }

            recoveryRequestedForGeneration = recoveryGeneration
            if (setReconnectingImmediately && currentStatus is VpnStatus.Connected) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Reconnecting...")
            }

            addLog("$reason; reconnecting transport")
            recoveryJob = null
            startTunnel(
                isMigration = true,
                forceFullRestart = fullRestart,
                expectedGeneration = recoveryGeneration
            )
        }
    }

    private fun refreshWakeLock(force: Boolean = false) {
        val lock = wakeLock ?: return
        val now = System.currentTimeMillis()
        if (!force &&
            lock.isHeld &&
            now - lastWakeLockRefreshAtMs < WAKE_LOCK_REFRESH_INTERVAL_MS
        ) {
            return
        }

        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRefreshAtMs = now
        }.onFailure {
            Log.w(TAG, "Failed to refresh VPN wake lock", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure {
            Log.w(TAG, "Failed to release VPN wake lock", it)
        }
        lastWakeLockRefreshAtMs = 0L
    }

    private fun scheduleTransportRetry(
        requestedGeneration: Long,
        reason: String,
        baseDelayMs: Long = RECONNECT_RETRY_BASE_DELAY_MS
    ) {
        val delayMs = nextReconnectRetryDelay(baseDelayMs)
        scheduleTransportRetryAfter(requestedGeneration, reason, delayMs)
    }

    private fun scheduleOlcRtcStartRetry(requestedGeneration: Long) {
        val delayMs = olcRtcStartRetryPolicy.retryDelayMillis(
            retriesScheduled = olcRtcStartRetryCount,
            baseDelayMillis = OLCRTC_START_RETRY_BASE_DELAY_MS,
            maxDelayMillis = RECONNECT_RETRY_MAX_DELAY_MS
        )
        olcRtcStartRetryCount++
        scheduleTransportRetryAfter(
            requestedGeneration,
            "transient olcRTC start failure",
            delayMs
        )
    }

    private fun scheduleTransportRetryAfter(
        requestedGeneration: Long,
        reason: String,
        delayMs: Long
    ) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            addLog("Retrying transport after $reason in ${delayMs / 1_000}s")
            delay(delayMs)
            if (generation != requestedGeneration) return@launch
            if (OlcboxVpnState.status.value !is VpnStatus.Reconnecting) return@launch

            recoveryJob = null
            startTunnel(
                isMigration = true,
                expectedGeneration = requestedGeneration
            )
        }
    }

    private fun nextReconnectRetryDelay(baseDelayMs: Long): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (baseDelayMs * multiplier).coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    private fun resetRecoveryState() {
        recoveryRequestedForGeneration = 0L
        reconnectAttempt = 0
        olcRtcStartRetryCount = 0
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun shouldRecreateTunnelOnRtcLoss(): Boolean {
        return connectionMode == AndroidConnectionMode.Tun
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> {
                val transportRunning = if (activeProfileType == VpnProfileConfig.TYPE_OLCRTC) {
                    olcRtcRuntime.isRunning
                } else {
                    externalEngine?.isRunning == true
                }
                val bridgeRunning = !requiresTunSocksBridge() || tunSocksBridge?.isRunning == true
                vpnInterface != null &&
                    tun2socksThread?.isAlive == true &&
                    transportRunning &&
                    bridgeRunning
            }
            AndroidConnectionMode.Proxy -> {
                if (activeProfileType == VpnProfileConfig.TYPE_OLCRTC) {
                    olcRtcRuntime.isRunning
                } else {
                    externalEngine?.isRunning == true
                }
            }
        }
    }

    private fun shouldRestartForStartCommand(): Boolean {
        return when (OlcboxVpnState.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting,
            VpnStatus.Stopping -> true
            VpnStatus.Disconnected,
            is VpnStatus.Error -> false
        } ||
            startupJob?.isActive == true ||
            cleanupJob?.isActive == true ||
            vpnInterface != null ||
            tun2socksThread != null ||
            socksProxy != null ||
            externalEngine != null ||
            tunSocksBridge != null ||
            olcRtcRuntime.isRunning
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        currentNetworkTransport = network?.transportOrNull()
        if (connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
    }

    private fun Network.transportOrNull(): UpstreamTransport? {
        val caps = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!caps.isUsableUpstream()) return null
        return caps.upstreamTransport()
    }

    private fun isBenignWifiRefresh(
        previousTransport: UpstreamTransport?,
        nextTransport: UpstreamTransport?
    ): Boolean {
        return previousTransport == UpstreamTransport.Wifi &&
            nextTransport == UpstreamTransport.Wifi
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                localizedNotificationText("Unified VPN connection"),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localizedNotificationText("VPN connection status and controls")
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        lastNotificationStatus = statusText
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        notificationForegroundStarted = true
    }

    private fun updateNotification(status: String) {
        lastNotificationStatus = status
        if (!notificationForegroundStarted) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val smallIcon = notificationSmallIcon()
        val largeIcon = runCatching {
            BitmapFactory.decodeResource(resources, applicationInfo.icon)
        }.getOrNull()
        val localizedStatus = localizedNotificationText(status)
        val contentText = activeProfileName
            .takeIf { it.isNotBlank() }
            ?.let { "$it · $localizedStatus" }
            ?: localizedStatus

        val publicNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Unified VPN")
            .setContentText(localizedStatus)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Unified VPN")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(smallIcon)
            .setLargeIcon(largeIcon)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setContentIntent(getProfileChooserPendingIntent())

        if (notificationProfileCount > 1) {
            builder.addAction(
                authenticatedNotificationAction(
                    icon = android.R.drawable.ic_media_previous,
                    label = "Previous",
                    pendingIntent = getServiceActionPendingIntent(
                        OlcboxVpnActions.ACTION_SWITCH_PREVIOUS_PROFILE,
                        NOTIFICATION_PREVIOUS_REQUEST_CODE
                    )
                )
            )
        }

        builder
            .addAction(
                authenticatedNotificationAction(
                    icon = android.R.drawable.ic_menu_close_clear_cancel,
                    label = "Stop",
                    pendingIntent = getServiceActionPendingIntent(
                        ACTION_STOP_VPN,
                        NOTIFICATION_STOP_REQUEST_CODE
                    )
                )
            )

        if (notificationProfileCount > 1) {
            builder.addAction(
                authenticatedNotificationAction(
                    icon = android.R.drawable.ic_media_next,
                    label = "Next",
                    pendingIntent = getServiceActionPendingIntent(
                        OlcboxVpnActions.ACTION_SWITCH_NEXT_PROFILE,
                        NOTIFICATION_NEXT_REQUEST_CODE
                    )
                )
            )
        }

        return builder
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun authenticatedNotificationAction(
        icon: Int,
        label: String,
        pendingIntent: PendingIntent
    ): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            icon,
            localizedNotificationText(label),
            pendingIntent
        )
            .setAuthenticationRequired(true)
            .build()
    }

    private fun notificationSmallIcon(): Int {
        @Suppress("DEPRECATION")
        val manifestIcon = runCatching {
            packageManager
                .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getInt(NOTIFICATION_SMALL_ICON_METADATA)
                ?.takeIf { it != 0 }
        }.getOrNull()
        if (manifestIcon != null) return manifestIcon

        val resourcePackage = runCatching {
            resources.getResourcePackageName(applicationInfo.icon)
        }.getOrDefault(ANDROID_APP_RESOURCE_PACKAGE)
        return resources.getIdentifier("ic_qs_tile", "mipmap", resourcePackage)
            .takeIf { it != 0 }
            ?: applicationInfo.icon
    }

    private fun getServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, OlcboxVpnService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getProfileChooserPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_OPEN_REQUEST_CODE,
            Intent(this, VpnProfileChooserActivity::class.java).apply {
                action = OlcboxVpnActions.ACTION_OPEN_PROFILE_CHOOSER
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
    }

    private fun setStatus(
        status: VpnStatus,
        allowWhileStopping: Boolean = false
    ) = synchronized(lifecycleTransitionLock) {
        if (lifecycleStopping &&
            !allowWhileStopping &&
            status !is VpnStatus.Stopping
        ) {
            return@synchronized
        }
        val connectedProxy = if (status is VpnStatus.Connected) {
            val usesAppCredentials = externalEngine?.usesAppSocksCredentials != false
            SubscriptionFetchProxy(
                host = socksConnectHost(),
                port = socksListenPort,
                username = socksUsername.takeIf { usesAppCredentials }.orEmpty(),
                password = socksPassword.takeIf { usesAppCredentials }.orEmpty()
            )
        } else {
            null
        }
        OlcboxVpnState.setStatus(status, connectedProxy)
        when (status) {
            VpnStatus.Connected -> {
                if (sessionConnectedAtElapsedRealtimeMs == null) {
                    sessionConnectedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
                }
                lastConnectedProfileStorageId = activeProfileStorageId
                publishSessionSnapshot(readTun2SocksStats())
            }

            VpnStatus.Disconnected -> {
                sessionConnectedAtElapsedRealtimeMs = null
                activeProfileStorageId = null
                lastConnectedProfileStorageId = null
                connectedTunnelGeneration = -1L
                activeProfileName = ""
                OlcboxVpnState.clearSession()
            }

            is VpnStatus.Error -> {
                sessionConnectedAtElapsedRealtimeMs = null
                publishSessionSnapshot(null)
            }

            else -> publishSessionSnapshot(null)
        }
    }

    private fun setConnectedForGeneration(
        requestedGeneration: Long,
        connectedProfileId: String?
    ): Boolean = synchronized(lifecycleTransitionLock) {
        if (requestedGeneration != generation ||
            connectedProfileId == null ||
            connectedProfileId != activeProfileStorageId
        ) {
            return@synchronized false
        }
        connectedTunnelGeneration = requestedGeneration
        lastConnectedProfileStorageId = connectedProfileId
        setStatus(VpnStatus.Connected)
        true
    }

    private fun publishSessionSnapshot(stats: Tun2SocksStats?) {
        OlcboxVpnState.setSession(
            VpnSessionSnapshot(
                activeProfileStorageId = activeProfileStorageId,
                connectedProfileStorageId = lastConnectedProfileStorageId,
                activeProfileName = activeProfileName,
                connectedAtElapsedRealtimeMs = sessionConnectedAtElapsedRealtimeMs,
                sentBytes = stats?.txBytes,
                receivedBytes = stats?.rxBytes
            )
        )
    }

    private fun activeModeLabel(): String {
        if (activeProfileType != VpnProfileConfig.TYPE_OLCRTC) {
            return when (activeProfileType) {
                VpnProfileConfig.TYPE_OPENFLUX -> "OpenFlux"
                VpnProfileConfig.TYPE_VLESS -> "VLESS"
                VpnProfileConfig.TYPE_AMNEZIA_WG -> "AmneziaWG"
                VpnProfileConfig.TYPE_AMNEZIA_VPN -> "AmneziaVPN"
                else -> "VPN"
            }
        }
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    private fun connectedNotificationText(): String = "${activeModeLabel()} Connected"

    private fun localizedNotificationText(text: String): String {
        return applicationContext.androidUiText(text)
    }

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "OlcboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "OlcboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return

                val userBytes = username.toByteArray()
                val passBytes = password.toByteArray()

                backendOut.write(SOCKS_AUTH_VERSION.toInt())
                backendOut.write(userBytes.size)
                backendOut.write(userBytes)
                backendOut.write(passBytes.size)
                backendOut.write(passBytes)
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != 0x00) return // 0x00 - успешно

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "OlcboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("olcbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = OlcboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = OlcboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val DEFAULT_OLCRTC_DNS_SERVER = "1.1.1.1:53"
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val MOBILE_STOP_TIMEOUT_MS = 5_000L
        private const val MOBILE_RUNTIME_STOP_WAIT_MS = 7_500L
        private const val MOBILE_RUNTIME_IDLE_QUICK_TIMEOUT_MS = 750L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val PREVIOUS_LIFECYCLE_WAIT_MS = 30_000L
        private const val FAILED_START_CLEANUP_WAIT_MS = 30_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 10_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_STALLED_TX_PACKET_DELTA = 8L
        private const val WATCHDOG_STALLED_SAMPLE_LIMIT = 3
        private const val RTC_RECOVERY_GRACE_MS = 2_500L
        private const val RTC_FAILURE_WINDOW_MS = 6_000L
        private const val RTC_FAILED_RECOVERY_THRESHOLD = 1
        private const val RTC_CLOSED_RECOVERY_THRESHOLD = 2
        private const val RTC_IO_ERROR_RECOVERY_THRESHOLD = 3
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 4_000L
        private const val OLCRTC_START_RETRY_BASE_DELAY_MS = 2_000L
        private const val NETWORK_RETRY_BASE_DELAY_MS = 8_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_BACKOFF_POWER = 3
        private const val MAX_OLCRTC_START_RETRIES = 5
        private const val NOTIFICATION_PROFILE_DEBOUNCE_MS = 250L
        private const val NOTIFICATION_PROFILE_SWITCH_POLL_MS = 100L
        private const val NOTIFICATION_PROFILE_SWITCH_TIMEOUT_MS = 4 * 60 * 1_000L
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        private const val TUN_DNS_SERVER = "1.1.1.1"
        private const val INTERNAL_SOCKS_USERNAME = "unifiedvpn"
        private const val INTERNAL_SOCKS_PASSWORD = "unifiedvpn"
        private const val NOTIFICATION_CHANNEL_ID = "olcbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val NOTIFICATION_OPEN_REQUEST_CODE = 100
        private const val NOTIFICATION_STOP_REQUEST_CODE = 101
        private const val NOTIFICATION_PREVIOUS_REQUEST_CODE = 102
        private const val NOTIFICATION_NEXT_REQUEST_CODE = 103
        private const val ANDROID_APP_RESOURCE_PACKAGE = "org.olcbox.app"
        private const val NOTIFICATION_SMALL_ICON_METADATA =
            "org.olcbox.app.NOTIFICATION_SMALL_ICON"
        private const val TAG = "OlcboxVpnService"

        private fun addLog(msg: String) {
            OlcboxVpnState.addLog(msg)
        }
    }
}
