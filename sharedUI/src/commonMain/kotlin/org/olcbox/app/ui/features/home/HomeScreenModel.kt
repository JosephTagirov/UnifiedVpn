package org.olcbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.logging.sanitizeDiagnosticLogLine
import org.olcbox.app.data.repository.LocationImportResult
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.share.FriendAccessPackageCodec
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.AutoProfileSpeed
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val locationsRepository: LocationsRepository,
    private val configImporter: ConfigImporter,
    private val logExporter: LogExporter,
    private val subscriptionRefreshDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeScreenState(
            isVpnConnected = false,
            isVpnLoading = false,
            selectedLocation = null,
            configData = LocationConfig(),
            activeProfile = null,
            shouldShowConfigInvalidReminder = false,
            canStartVpn = false,
            startBlockedReason = "Add a location first"
        )
    )
    private val subscriptionRefreshWake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val subscriptionRefreshMutex = Mutex()
    private var loadCurrentConfigRequest = 0L
    private var homeScreenResumed = false
    private var autoSpeedJob: Job? = null
    private var autoSpeedProfileId: String? = null
    private var lastSpeedCandidateId: String? = null
    private var lastSpeedSwitch: TimeMark? = null
    val state get() = _state.asStateFlow()
    val logs get() = vpnManager.logs
    val supportsAutoSpeed get() = vpnManager.supportsAutoSpeed

    init {
        startSubscriptionAutoRefresh()

        vpnManager.adBlockingUnavailable?.let { warning ->
            viewModelScope.launch {
                warning.collect { unavailable -> _state.update { it.copy(adBlockingUnavailable = unavailable) } }
            }
        }

        viewModelScope.launch {
            locationsRepository.changes
                .collect {
                    loadCurrentConfigNow()
                    subscriptionRefreshWake.tryEmit(Unit)
                }
        }

        viewModelScope.launch {
            vpnManager.status.collect { status ->
                _state.update {
                    val current = it.copy(connectionError = (status as? VpnStatus.Error)?.message?.let(::sanitizeDiagnosticLogLine))
                    when (status) {
                        VpnStatus.Connected -> current.copy(isVpnConnected = true, isVpnLoading = false)
                        VpnStatus.Connecting -> current.copy(isVpnConnected = false, isVpnLoading = true)
                        VpnStatus.Reconnecting -> current.copy(isVpnConnected = true, isVpnLoading = true)
                        VpnStatus.Stopping -> current.copy(isVpnConnected = false, isVpnLoading = false)
                        VpnStatus.Disconnected -> current.copy(isVpnConnected = false, isVpnLoading = false)
                        is VpnStatus.Error -> current.copy(isVpnConnected = false, isVpnLoading = false)
                    }
                }
            }
        }
    }

    fun loadCurrentConfig(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            loadCurrentConfigNow()
            onComplete()
        }
    }

    private suspend fun loadCurrentConfigNow() {
        val requestId = ++loadCurrentConfigRequest
        val active = locationsRepository.getActiveLocation()
        if (requestId != loadCurrentConfigRequest) return
        if (active == null) {
            _state.update {
                it.copy(
                    selectedLocation = null,
                    configData = LocationConfig(),
                    activeProfile = null,
                    canStartVpn = false,
                    startBlockedReason = "Add a location first"
                )
            }
            return
        }

        val normalized = active.location
        val profile = active.profile
        val locationItem = LocationItem(
            storageId = active.storageId,
            fullName = active.displayName(),
            config = normalized,
            profile = profile,
            subscriptionUrl = active.subscriptionUrl,
            metadata = active.metadata
        )
        val isComplete = active.isComplete()

        _state.update {
            it.copy(
                configData = normalized,
                activeProfile = profile,
                selectedLocation = locationItem,
                canStartVpn = isComplete,
                startBlockedReason = if (isComplete) null else "Complete active profile first"
            )
        }
    }

    suspend fun performPing(): Long? {
        val current = _state.value
        return vpnManager.ping(
            locationConfig = current.configData,
            profile = current.activeProfile ?: VpnProfileConfig.olcRtc()
        )
    }

    suspend fun performPingFor(
        config: LocationConfig,
        profile: VpnProfileConfig = VpnProfileConfig.olcRtc()
    ): Long? {
        return vpnManager.ping(config, profile)
    }

    fun setAutoSpeedEnabled(enabled: Boolean) {
        if (enabled == _state.value.autoSpeedEnabled || !supportsAutoSpeed) return
        autoSpeedProfileId = _state.value.selectedLocation?.storageId
        lastSpeedCandidateId = null
        _state.update { it.copy(autoSpeedEnabled = enabled, autoSpeedMessage = null) }
        updateAutoSpeedJob()
    }

    fun setHomeScreenResumed(resumed: Boolean) {
        homeScreenResumed = resumed
        updateAutoSpeedJob()
    }

    private fun updateAutoSpeedJob() {
        if (!homeScreenResumed || !_state.value.autoSpeedEnabled) {
            autoSpeedJob?.cancel()
            autoSpeedJob = null
            return
        }
        if (autoSpeedJob?.isActive == true) return
        autoSpeedJob = viewModelScope.launch {
            vpnManager.status.collectLatest { status ->
                if (status != VpnStatus.Connected) return@collectLatest
                while (true) {
                    val waitAfterSwitch = lastSpeedSwitch?.let { 10.minutes - it.elapsedNow() }
                    if (waitAfterSwitch != null && waitAfterSwitch.isPositive()) delay(waitAfterSwitch)
                    try {
                        compareProfileSpeeds()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _state.update { it.copy(autoSpeedMessage = "Speed comparison is unavailable for this connection") }
                    }
                    delay(5.minutes)
                }
            }
        }
    }

    private suspend fun compareProfileSpeeds() {
        val revision = locationsRepository.changes.value
        val bundle = locationsRepository.getBundle()
        val active = bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId } ?: return
        if (autoSpeedProfileId != null && autoSpeedProfileId != active.storageId) {
            // A manual selection can also come from Android's notification.
            setAutoSpeedEnabled(false)
            return
        }
        autoSpeedProfileId = active.storageId
        val candidate = AutoProfileSpeed.nextCandidate(active, bundle.locations, lastSpeedCandidateId) ?: return
        val connection = vpnManager.autoSpeedConnection()
        if (connection == null) {
            _state.update { it.copy(autoSpeedMessage = "Speed comparison is unavailable for this connection") }
            return
        }

        lastSpeedCandidateId = candidate.storageId
        _state.update { it.copy(autoSpeedMessage = null) }
        val measurement = AutoProfileSpeed.confirmImprovement(
            measureCurrent = { vpnManager.measureProfileSpeed(active, isCurrent = true) },
            measureCandidate = { vpnManager.measureProfileSpeed(candidate, isCurrent = false) },
            sameConnection = { vpnManager.autoSpeedConnection() == connection }
        ) ?: return
        if (!speedSwitchStillAllowed()) return
        withContext(NonCancellable) {
            val selectedAtRevision =
                locationsRepository.compareAndSetActiveLocationId(revision, candidate.storageId) ?: return@withContext
            try {
                if (vpnManager.autoSpeedConnection() != connection ||
                    !speedSwitchStillAllowed() || locationsRepository.changes.value != selectedAtRevision
                ) {
                    locationsRepository.compareAndSetActiveLocationId(selectedAtRevision, active.storageId)
                    return@withContext
                }
                vpnManager.startVpn()
            } catch (error: Exception) {
                locationsRepository.compareAndSetActiveLocationId(selectedAtRevision, active.storageId)
                throw error
            }
            autoSpeedProfileId = candidate.storageId
            lastSpeedSwitch = TimeSource.Monotonic.markNow()
            _state.update { it.copy(autoSpeedMessage = "Connecting to a faster profile") }
            vpnManager.logAutoSpeedSwitch(
                "Auto speed selected ${candidate.storageId} in place of ${active.storageId} " +
                    "after two HTTPS download comparisons " +
                    "(${measurement.currentBitsPerSecond / 1_000} to ${measurement.candidateBitsPerSecond / 1_000} kbit/s)"
            )
        }
    }

    private fun speedSwitchStillAllowed(): Boolean =
        homeScreenResumed && _state.value.autoSpeedEnabled &&
            !_state.value.isVpnLoading && vpnManager.status.value == VpnStatus.Connected

    suspend fun checkConnectionFor(config: LocationConfig): Long? {
        return vpnManager.checkConnection(config)
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true) }
    }

    fun ToggleVpn() {
        val status = vpnManager.status.value
        if (_state.value.isVpnLoading ||
            status is VpnStatus.Connecting ||
            status is VpnStatus.Reconnecting
        ) {
            viewModelScope.launch {
                vpnManager.stopVpn()
                _state.update { it.copy(isVpnConnected = false, isVpnLoading = false) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            try {
                if (_state.value.isVpnConnected || vpnManager.status.value is VpnStatus.Connected) {
                    vpnManager.stopVpn()
                } else {
                    val active = locationsRepository.getActiveLocation()
                    if (active == null || !active.isComplete()) {
                        _state.update {
                            it.copy(
                                isVpnLoading = false,
                                canStartVpn = false,
                                startBlockedReason = "Add a valid VPN profile first"
                            )
                        }
                        return@launch
                    }
                    vpnManager.startVpn()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false) }
            }
        }
    }

    fun shouldRestartVpnAfterProfileSelection(): Boolean {
        return vpnManager.status.value.shouldRestartAfterProfileSelection()
    }

    fun restartVpnIfRunning(force: Boolean = false, expectedLocationId: String? = null) {
        if (!force && !vpnManager.status.value.shouldRestartAfterProfileSelection()) return

        viewModelScope.launch {
            val active = locationsRepository.getActiveLocation()
            if (expectedLocationId != null && active?.storageId != expectedLocationId) return@launch
            _state.update { it.copy(isVpnLoading = true) }
            if (active == null || !active.isComplete()) {
                vpnManager.stopVpn()
                loadCurrentConfigNow()
                _state.update {
                    it.copy(
                        isVpnConnected = false,
                        isVpnLoading = false,
                        canStartVpn = false,
                        startBlockedReason = "Add a valid location first"
                    )
                }
            } else {
                vpnManager.startVpn()
            }
        }
    }
    private fun updateLocationConfig(block: (LocationConfig) -> LocationConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }
    fun onCopyFullConfigClicked() {
        viewModelScope.launch {
            configImporter.copyToClipboard(locationsRepository.exportBundle())
        }
    }

    fun onCreateFriendAccessPackage(
        vlessUri: String,
        awgConfig: String,
        onCreated: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                FriendAccessPackageCodec.encode(
                    FriendAccessPackageCodec.create(
                        source = locationsRepository.getBundle(),
                        vlessUri = vlessUri,
                        awgConfig = awgConfig
                    )
                )
            }.onSuccess(onCreated).onFailure { failure ->
                onError(failure.message ?: "Could not create friend package")
            }
        }
    }

    fun suggestedLogsFileName(): String = "unified-vpn-logs.txt"

    fun onSaveLogsToFile(
        target: Any,
        onSaved: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.writeLogs(target, content)
                .onSuccess { savedPath ->
                    onSaved(
                        if (savedPath.isBlank() || savedPath == "Logs saved") {
                            "Logs saved"
                        } else {
                            "Logs saved to $savedPath"
                        }
                    )
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to save logs")
                }
        }
    }

    fun onShareLogs(
        onShared: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.shareLogs(content)
                .onSuccess { message -> onShared(message) }
                .onFailure { error -> onError(error.message ?: "Failed to share logs") }
        }
    }

    fun onPasteFromClipboard(
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(
                rawText = text,
                onComplete = onComplete,
                onError = onError
            )
        } ?: onError("No clipboard data found")
    }

    fun readImportTextFromClipboard(
        onText: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        configImporter.getFromClipboard()
            ?.takeIf { it.isNotBlank() }
            ?.let(onText)
            ?: onError("No clipboard data found")
    }

    fun readImportTextFromSource(
        source: Any,
        onText: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            configImporter.readTextFromSource(source)
                ?.takeIf { it.isNotBlank() }
                ?.let(onText)
                ?: onError("Could not read config file")
        }
    }

    fun onFileSelected(
        fileSource: Any,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val text = configImporter.readTextFromSource(fileSource)
            if (text == null) {
                onError("Could not read config file")
            } else {
                onImportFullConfig(
                    rawText = text,
                    onComplete = onComplete,
                    onError = onError
                )
            }
        }
    }

    fun onImportFullConfig(
        rawText: String,
        subscriptionRefreshIntervalMs: Long? = null,
        allowInsecureSubscriptionRequests: Boolean = false,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (rawText.isBlank()) {
            onError("No config text found")
            return
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    locationsRepository.importTextDetailed(
                        text = rawText,
                        subscriptionProxy = vpnManager.subscriptionFetchProxy(),
                        allowInsecureRequests = allowInsecureSubscriptionRequests
                    )
                }
                when (result) {
                    is LocationImportResult.Success -> {
                        if (result.subscriptionUrl != null && subscriptionRefreshIntervalMs != null) {
                            locationsRepository.setSubscriptionUpdateInterval(
                                result.subscriptionUrl,
                                subscriptionRefreshIntervalMs
                            )
                        }
                        loadCurrentConfigNow()
                        onComplete()
                    }
                    is LocationImportResult.Failure -> onError(result.message)
                }
            } catch (e: Exception) {
                val message = e.message ?: "Import failed"
                onError(message)
            }
        }
    }

    fun setSubscriptionRefreshInterval(
        subscriptionUrl: String,
        intervalMs: Long?,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            locationsRepository.setSubscriptionUpdateInterval(subscriptionUrl, intervalMs)
            onComplete()
        }
    }

    fun deleteSubscription(
        subscriptionUrl: String,
        onComplete: (removedLocations: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val removedLocations = locationsRepository.deleteSubscription(subscriptionUrl)
            loadCurrentConfigNow()
            onComplete(removedLocations)
        }
    }

    fun refreshSubscriptions(
        onComplete: (updatedCount: Int) -> Unit = {}
    ) = refreshSubscriptionData(null, onComplete)

    fun refreshSubscription(
        subscriptionUrl: String,
        onComplete: (updatedCount: Int) -> Unit = {}
    ) = refreshSubscriptionData(subscriptionUrl.trim(), onComplete)

    private fun refreshSubscriptionData(subscriptionUrl: String?, onComplete: (Int) -> Unit) {
        if (_state.value.isRefreshingSubscriptions) {
            onComplete(0)
            return
        }
        _state.update { it.copy(isRefreshingSubscriptions = true, subscriptionRefreshMessage = null, subscriptionRefreshFailed = false) }
        viewModelScope.launch {
            var updatedCount = 0
            try {
                subscriptionRefreshMutex.withLock {
                    val before = locationsRepository.getActiveLocation()
                    val requestedCount = locationsRepository.getAllLocations()
                        .mapNotNull { it.subscriptionUrl?.trim()?.takeIf(String::isNotBlank) }
                        .filter { subscriptionUrl == null || it == subscriptionUrl }.toSet().size
                    updatedCount = if (subscriptionUrl == null) {
                        locationsRepository.refreshSubscriptions(vpnManager.subscriptionFetchProxy())
                    } else {
                        locationsRepository.refreshSubscription(subscriptionUrl, vpnManager.subscriptionFetchProxy())
                    }
                    loadCurrentConfigNow()
                    val activeWasRemoved = updatedCount > 0 && applySubscriptionProfileChange(before)
                    val failed = updatedCount < requestedCount
                    val message = when {
                        requestedCount == 0 -> "No subscriptions to update"
                        updatedCount == 0 -> "Could not update subscriptions. Saved profiles were kept."
                        activeWasRemoved -> SUBSCRIPTION_REMOVED_ACTIVE_PROFILE
                        failed -> "Subscriptions updated: $updatedCount. Some subscriptions could not be refreshed."
                        else -> "Subscriptions updated: $updatedCount"
                    }
                    _state.update { it.copy(subscriptionRefreshMessage = message, subscriptionRefreshFailed = failed) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _state.update { it.copy(subscriptionRefreshMessage = "Could not update subscriptions. Saved profiles were kept.", subscriptionRefreshFailed = true) }
            } finally {
                _state.update { it.copy(isRefreshingSubscriptions = false) }
            }
            onComplete(updatedCount)
        }
    }

    private fun startSubscriptionAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                try {
                    refreshDueSubscriptionsIfNeeded()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep the scheduler alive; failed subscriptions retain their last good config.
                }
                val nextRefreshAt = locationsRepository.nextSubscriptionRefreshAtEpochMs()
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val waitMs = nextRefreshAt
                    ?.minus(now)
                    ?.coerceAtLeast(MIN_SUBSCRIPTION_REFRESH_WAIT_MS)
                    ?: IDLE_SUBSCRIPTION_REFRESH_WAIT_MS
                withTimeoutOrNull(waitMs) {
                    subscriptionRefreshWake.first()
                }
            }
        }
    }

    fun onForeground() {
        loadCurrentConfig()
        subscriptionRefreshWake.tryEmit(Unit)
    }

    private suspend fun refreshDueSubscriptionsIfNeeded() {
      subscriptionRefreshMutex.withLock {
        val before = locationsRepository.getActiveLocation()
        val updatedCount = withContext(subscriptionRefreshDispatcher) {
            locationsRepository.refreshDueSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
        }
        if (updatedCount > 0) {
            loadCurrentConfigNow()
            if (applySubscriptionProfileChange(before)) {
                _state.update { it.copy(subscriptionRefreshMessage = SUBSCRIPTION_REMOVED_ACTIVE_PROFILE) }
            }
        }
      }
    }

    private suspend fun applySubscriptionProfileChange(before: LocationEntry?): Boolean {
        if (before == null) return false
        val bundle = locationsRepository.getBundle()
        val updated = bundle.locations.firstOrNull { it.storageId == before.storageId }
        if (updated == null) {
            // Removing the current entry can preselect a replacement in the picker.
            if (vpnManager.status.value.shouldRestartAfterProfileSelection()) vpnManager.stopVpn()
            return true
        }
        if (bundle.activeLocationId == before.storageId && subscriptionRefreshChangesActiveProfile(before, updated)) {
            restartVpnIfRunning(expectedLocationId = before.storageId)
        }
        return false
    }

    private fun buildLogsExport(logs: List<String>): String = org.olcbox.app.data.logging.LogPresentation.export(logs)

}

data class HomeScreenState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: LocationConfig,
    val activeProfile: VpnProfileConfig?,
    val shouldShowConfigInvalidReminder: Boolean,
    val canStartVpn: Boolean,
    val startBlockedReason: String?,
    val connectionError: String? = null,
    val isRefreshingSubscriptions: Boolean = false,
    val subscriptionRefreshMessage: String? = null,
    val subscriptionRefreshFailed: Boolean = false,
    val autoSpeedEnabled: Boolean = false,
    val autoSpeedMessage: String? = null,
    val adBlockingUnavailable: Boolean = false
)

internal fun subscriptionRefreshChangesActiveProfile(before: LocationEntry?, after: LocationEntry?): Boolean {
    if (before == null || after == null) return before != after
    if (before.storageId != after.storageId) return true
    fun connectionProfile(entry: LocationEntry): VpnProfileConfig = entry.profile.normalized().let { profile ->
        profile.copy(name = null, uri = profile.uri?.substringBefore('#'),
            rawConfig = profile.rawConfig?.let { if (it.startsWith("vless://", true)) it.substringBefore('#') else it })
    }
    return connectionProfile(before) != connectionProfile(after) ||
        (before.profile.isOlcRtc() && before.location.normalized().copy(name = "") != after.location.normalized().copy(name = ""))
}

private const val MIN_SUBSCRIPTION_REFRESH_WAIT_MS = 1_000L
private const val IDLE_SUBSCRIPTION_REFRESH_WAIT_MS = 24L * 60L * 60L * 1_000L
private const val SUBSCRIPTION_REMOVED_ACTIVE_PROFILE = "Subscription removed the selected profile. Choose a profile to connect."

internal fun VpnStatus.shouldRestartAfterProfileSelection(): Boolean {
    return this is VpnStatus.Connected ||
        this is VpnStatus.Connecting ||
        this is VpnStatus.Reconnecting
}
