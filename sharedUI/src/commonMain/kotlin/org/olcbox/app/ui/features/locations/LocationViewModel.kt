package org.olcbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.vpn.VpnPingUnavailableException

data class LocationItem(
    val storageId: String,
    val fullName: String,
    val config: LocationConfig? = null,
    val profile: VpnProfileConfig = VpnProfileConfig.olcRtc(),
    val subscriptionUrl: String? = null,
    val metadata: LocationMetadata? = null
)

internal fun LocationItem.reorderGroupKey(): String {
    val normalizedUrl = subscriptionUrl?.trim().orEmpty()
    if (normalizedUrl.isBlank()) return CUSTOM_PROFILE_REORDER_GROUP
    val subscriptionName = metadata?.subscription?.name?.trim().orEmpty()
    return "$subscriptionName|$normalizedUrl"
}

internal const val CUSTOM_PROFILE_REORDER_GROUP = "custom-profiles"

private fun LocationItem.hasSamePingTarget(other: LocationItem): Boolean {
    val currentProfile = profile.normalized().copy(name = null)
    val otherProfile = other.profile.normalized().copy(name = null)
    if (currentProfile.normalizedType != otherProfile.normalizedType) return false
    return when {
        currentProfile.isOlcRtc() ->
            (config ?: LocationConfig()).normalized().copy(name = "") ==
                (other.config ?: LocationConfig()).normalized().copy(name = "")
        currentProfile.isOpenFlux() -> {
            val currentConfig = OpenFluxProfileConfig.parse(currentProfile.rawConfig ?: currentProfile.uri)
                ?: return false
            currentConfig == OpenFluxProfileConfig.parse(otherProfile.rawConfig ?: otherProfile.uri)
        }
        else -> currentProfile == otherProfile
    }
}

sealed class PingsState {
    open val unavailable: Map<String, String> = emptyMap()

    object Idle : PingsState()

    data class Loading(
        val lastPings: Map<String, Int?>? = null,
        val currentPings: Map<String, Int?> = emptyMap(),
        val pendingLocationIds: Set<String> = emptySet(),
        val completed: Int = 0,
        val total: Int = 0,
        override val unavailable: Map<String, String> = emptyMap()
    ) : PingsState()

    data class Success(
        val pings: Map<String, Int?>,
        override val unavailable: Map<String, String> = emptyMap()
    ) : PingsState()

    data class Error(
        val message: String,
        val lastPings: Map<String, Int?>? = null
    ) : PingsState()
}

class LocationViewModel(
    private val locationsRepository: LocationsRepository,
) : ViewModel() {

    var locations = mutableStateListOf<LocationItem>()
        private set

    var selectedLocationId by mutableStateOf<String?>(null)
        private set

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    private val activePingJobs = mutableMapOf<String, Job>()
    private val unavailablePings = mutableMapOf<String, String>()
    private val pingSemaphore = Semaphore(LOCATION_PING_PARALLELISM)
    private var loadLocationsJob: Job? = null
    private var loadLocationsRequest = 0
    private var moveLocationJob: Job? = null
    private var moveLocationRequest = 0
    private val providerDrafts = mutableMapOf<String, ProviderDraft>()

    var editingConfig by mutableStateOf(LocationConfig())
    var editingProfile by mutableStateOf(VpnProfileConfig.olcRtc())
        private set
    var editingOpenFluxConfig by mutableStateOf(OpenFluxProfileConfig())
        private set
    var editingLocalSocksPort by mutableStateOf("")
        private set
    var editingName by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)
    var editingServiceProvider by mutableStateOf(LocationConfig.DEFAULT_BYPASS_PROVIDER)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var serverError by mutableStateOf<String?>(null)
        private set

    var keyError by mutableStateOf<String?>(null)
        private set

    var dnsError by mutableStateOf<String?>(null)
        private set

    var profileError by mutableStateOf<String?>(null)
        private set

    var openFluxDocumentUrlError by mutableStateOf<String?>(null)
        private set

    var openFluxEncryptionKeyError by mutableStateOf<String?>(null)
        private set

    val openFluxTransportError: String?
        get() = if (editingOpenFluxConfig.normalized().transport in OpenFluxProfileConfig.supportedTransports) {
            null
        } else {
            "Unsupported OpenFlux transport"
        }

    var localSocksPortError by mutableStateOf<String?>(null)
        private set

    val isEditingOlcRtc: Boolean
        get() = editingProfile.isOlcRtc()

    val isEditingOpenFlux: Boolean
        get() = editingProfile.isOpenFlux()

    val isFormValid: Boolean
        get() = if (isEditingOlcRtc) {
            nameError == null &&
                serverError == null &&
                keyError == null &&
                dnsError == null &&
                editingName.isNotBlank() &&
                editingConfig.id.isNotBlank() &&
                editingConfig.key.isNotBlank()
        } else {
            nameError == null &&
                profileError == null &&
                (isEditingOpenFlux || localSocksPortError == null) &&
                editingName.isNotBlank() &&
                editingExternalProfile().isCompleteFor()
        }

    init {
        viewModelScope.launch {
            locationsRepository.changes
                .collect {
                    loadLocations()
                }
        }
    }

    fun loadLocations(onComplete: () -> Unit = {}) {
        val requestId = ++loadLocationsRequest
        loadLocationsJob?.cancel()
        loadLocationsJob = viewModelScope.launch {
            val bundle = locationsRepository.getBundle()
            val savedConfigs = bundle.locations
            val currentSelectedId = bundle.activeLocationId

            val nextLocations = savedConfigs.map { entry ->
                val normalized = entry.location
                LocationItem(
                    storageId = entry.storageId,
                    fullName = entry.displayName(),
                    config = normalized,
                    profile = entry.profile,
                    subscriptionUrl = entry.subscriptionUrl,
                    metadata = entry.metadata
                )
            }

            if (requestId != loadLocationsRequest) return@launch

            invalidateChangedPings(nextLocations)
            locations.clear()
            locations.addAll(nextLocations)

            val nextSelectedId = if (
                nextLocations.isNotEmpty() &&
                (
                        currentSelectedId.isNullOrBlank() ||
                                nextLocations.none { it.storageId == currentSelectedId }
                        )
            ) {
                nextLocations.firstOrNull()?.storageId
            } else {
                currentSelectedId
            }
            if (
                nextSelectedId != currentSelectedId &&
                nextLocations.any { it.storageId == nextSelectedId }
            ) {
                locationsRepository.setActiveLocationId(nextSelectedId)
            }

            if (requestId != loadLocationsRequest) return@launch

            selectedLocationId = nextSelectedId
            onComplete()
        }
    }

    fun selectLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.setActiveLocationId(id)
            selectedLocationId = id
            onComplete()
        }
    }

    fun refreshPings(
        targetLocationIds: List<String>? = null,
        performPing: suspend (LocationConfig, VpnProfileConfig) -> Long?,
        onComplete: (onlineCount: Int, totalCount: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        val previousPings = currentPingsSnapshot()
        val locationsSnapshot = locations.toList()

        val pingableLocations = locationsSnapshot
            .filter { location ->
                location.profile.isCompleteFor(location.config) &&
                        (targetLocationIds == null || targetLocationIds.contains(location.storageId))
            }
            .filterNot { location ->
                activePingJobs.containsKey(location.storageId)
            }

        if (locationsSnapshot.isEmpty()) {
            if (activePingJobs.isEmpty()) {
                pingsState = PingsState.Success(emptyMap())
            }
            onComplete(0, 0)
            return
        }

        if (pingableLocations.isEmpty()) {
            emitPingState(previousPings)
            onComplete(0, 0)
            return
        }

        var completedForThisRequest = 0
        var onlineForThisRequest = 0
        var unavailableForThisRequest = 0
        val totalForThisRequest = pingableLocations.size
        val jobsToStart = mutableListOf<Job>()

        pingableLocations.forEach { location ->
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                val pingJob = coroutineContext[Job]
                try {
                    unavailablePings.remove(location.storageId)
                    var unavailableReason: String? = null
                    val ping = try {
                        pingSemaphore.withPermit {
                            checkLocationPing(location, performPing)?.toInt()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: VpnPingUnavailableException) {
                        unavailableReason = e.message.orEmpty()
                        null
                    } catch (_: Exception) {
                        null
                    }

                    coroutineContext.ensureActive()
                    if (activePingJobs[location.storageId] !== pingJob ||
                        locations.none { it.storageId == location.storageId && it.hasSamePingTarget(location) }
                    ) return@launch

                    val updatedPings = currentPingsSnapshot().toMutableMap()
                    if (unavailableReason != null) {
                        unavailablePings[location.storageId] = unavailableReason
                        unavailableForThisRequest++
                        updatedPings.remove(location.storageId)
                    } else {
                        updatedPings[location.storageId] = ping
                    }

                    activePingJobs.remove(location.storageId)

                    if (ping != null) {
                        onlineForThisRequest++
                    }

                    completedForThisRequest++

                    emitPingState(updatedPings.toMap())

                    if (completedForThisRequest == totalForThisRequest) {
                        onComplete(onlineForThisRequest, totalForThisRequest - unavailableForThisRequest)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (activePingJobs[location.storageId] === pingJob) {
                        val message = e.message ?: "HTTP ping failed"
                        onError(message)
                    }
                } finally {
                    // A cancelled old request must not remove a replacement for the same row.
                    if (activePingJobs[location.storageId] === pingJob) {
                        activePingJobs.remove(location.storageId)
                        emitPingState()
                    }
                }
            }

            activePingJobs[location.storageId] = job
            jobsToStart.add(job)
        }

        emitPingState(previousPings)
        jobsToStart.forEach { it.start() }
    }

    private fun invalidateChangedPings(nextLocations: List<LocationItem>) {
        val nextById = nextLocations.associateBy { it.storageId }
        val changedIds = locations.filter { current ->
            val next = nextById[current.storageId]
            next == null || !current.hasSamePingTarget(next)
        }.mapTo(mutableSetOf()) { it.storageId }
        if (changedIds.isEmpty()) return

        val remainingPings = currentPingsSnapshot().filterKeys { it !in changedIds }
        changedIds.forEach { id ->
            activePingJobs.remove(id)?.cancel()
            unavailablePings.remove(id)
        }
        emitPingState(remainingPings)
    }

    private fun currentPingsSnapshot(): Map<String, Int?> {
        return when (val state = pingsState) {
            PingsState.Idle -> emptyMap()

            is PingsState.Loading -> {
                state.currentPings.ifEmpty {
                    state.lastPings.orEmpty()
                }
            }

            is PingsState.Success -> {
                state.pings
            }

            is PingsState.Error -> {
                state.lastPings.orEmpty()
            }
        }
    }

    private fun emitPingState(
        pings: Map<String, Int?> = currentPingsSnapshot()
    ) {
        val pendingIds = activePingJobs.keys.toSet()

        pingsState = if (pendingIds.isEmpty()) {
            PingsState.Success(pings, unavailable = unavailablePings.toMap())
        } else {
            PingsState.Loading(
                lastPings = pings,
                currentPings = pings,
                pendingLocationIds = pendingIds,
                completed = 0,
                total = pendingIds.size,
                unavailable = unavailablePings.toMap()
            )
        }
    }

    private suspend fun checkLocationPing(
        location: LocationItem,
        performPing: suspend (LocationConfig, VpnProfileConfig) -> Long?
    ): Long? {
        val config = location.config ?: LocationConfig()
        if (!location.profile.isCompleteFor(config)) return null

        return withTimeoutOrNull(LOCATION_PING_TIMEOUT_MS) {
            repeat(LOCATION_PING_ATTEMPTS) { attempt ->
                val result = try {
                    performPing(config, location.profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VpnPingUnavailableException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (result != null) {
                    return@withTimeoutOrNull result
                }

                if (attempt < LOCATION_PING_ATTEMPTS - 1) {
                    delay(LOCATION_PING_RETRY_DELAY_MS)
                }
            }

            null
        }
    }

    fun startCreating(profileType: String) {
        startEditing(null)
        editingProfile = VpnProfileConfig(type = VpnProfileConfig.normalizeType(profileType))
    }

    fun startEditing(id: String?) {
        nameError = null
        serverError = null
        keyError = null
        dnsError = null
        profileError = null
        openFluxDocumentUrlError = null
        openFluxEncryptionKeyError = null
        localSocksPortError = null
        isSaving = false
        providerDrafts.clear()

        if (id == null) {
            editingId = null
            editingConfig = LocationConfig()
            editingProfile = VpnProfileConfig.olcRtc()
            editingLocalSocksPort = ""
            editingName = ""
        } else {
            val location = locations.find { it.storageId == id }
            editingId = id
            editingConfig = location?.config?.normalized() ?: LocationConfig()
            editingProfile = location?.profile?.normalized() ?: VpnProfileConfig.olcRtc()
            editingLocalSocksPort = editingProfile.localSocksPort?.toString().orEmpty()
            editingName = location?.fullName ?: editingConfig.displayName()
        }
        editingOpenFluxConfig = if (isEditingOpenFlux) {
            OpenFluxProfileConfig.parse(
                editingProfile.rawConfig?.takeIf(String::isNotBlank) ?: editingProfile.uri
            ) ?: OpenFluxProfileConfig()
        } else {
            OpenFluxProfileConfig()
        }
        val provider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        editingServiceProvider = if (provider == LocationConfig.PROVIDER_JITSI) {
            LocationConfig.DEFAULT_BYPASS_PROVIDER
        } else {
            provider
        }
        providerDrafts[provider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )
    }

    fun onNameChanged(value: String) {
        editingName = value
        validateName(value)
        if (!isEditingOlcRtc) validateExternalProfile()
    }

    fun onProfileTypeChanged(value: String) {
        editingProfile = editingProfile.copy(type = VpnProfileConfig.normalizeType(value))
        validateExternalProfile()
    }

    fun onProfileUriChanged(value: String) {
        editingProfile = editingProfile.copy(uri = value)
        if (isEditingOpenFlux) {
            editingOpenFluxConfig = OpenFluxProfileConfig.parse(value) ?: OpenFluxProfileConfig()
        }
        validateExternalProfile()
    }

    fun onProfileRawConfigChanged(value: String) {
        editingProfile = editingProfile.copy(rawConfig = value)
        if (isEditingOpenFlux) {
            editingOpenFluxConfig = OpenFluxProfileConfig.parse(value) ?: OpenFluxProfileConfig()
        }
        validateExternalProfile()
    }

    fun onOpenFluxTransportChanged(value: String) {
        editingOpenFluxConfig = editingOpenFluxConfig.copy(transport = value.trim().lowercase())
        validateExternalProfile()
    }

    fun onOpenFluxDocumentUrlChanged(value: String) {
        editingOpenFluxConfig = editingOpenFluxConfig.copy(documentUrl = value)
        validateExternalProfile()
    }

    fun onOpenFluxEncryptionKeyChanged(value: String) {
        editingOpenFluxConfig = editingOpenFluxConfig.copy(encryptionKey = value)
        validateExternalProfile()
    }

    fun onLocalSocksHostChanged(value: String) {
        editingProfile = editingProfile.copy(
            localSocksHost = value.replace("\r", "").replace("\n", "")
        )
    }

    fun onLocalSocksPortChanged(value: String) {
        editingLocalSocksPort = value.filter(Char::isDigit).take(5)
        localSocksPortError = when {
            editingLocalSocksPort.isBlank() -> null
            editingLocalSocksPort.toIntOrNull() !in 1..65535 -> "Port must be between 1 and 65535"
            else -> null
        }
        validateExternalProfile()
    }

    fun onServerChanged(value: String) {
        editingConfig = editingConfig.copy(id = value)
        validateServer(value)
    }

    fun onSniChanged(value: String) = Unit

    fun onPasswordChanged(value: String) {
        editingConfig = editingConfig.copy(key = value)
        validateKey(value)
    }

    fun onBypassProviderChanged(value: String) {
        val provider = LocationConfig.normalizeProvider(value)
        val currentProvider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        if (provider == currentProvider) return

        providerDrafts[currentProvider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )

        if (provider != LocationConfig.PROVIDER_JITSI) {
            editingServiceProvider = provider
        }

        val restored = providerDrafts[provider] ?: ProviderDraft()

        editingConfig = editingConfig.copy(
            bypassProvider = provider,
            transport = if (provider == LocationConfig.PROVIDER_JITSI) {
                LocationConfig.TRANSPORT_DATACHANNEL
            } else {
                LocationConfig.normalizeTransport(editingConfig.transport, provider)
            },
            id = restored.room,
            key = restored.key
        )
        serverError = null
        keyError = null
    }

    fun onTransportChanged(value: String) {
        editingConfig = editingConfig.copy(
            transport = LocationConfig.normalizeTransport(value, editingConfig.bypassProvider)
        )
    }

    fun onVp8FpsChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Fps = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onVp8BatchChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Batch = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onDnsServerChanged(value: String) {
        editingConfig = editingConfig.copy(
            dnsServer = value
                .replace("\r", "")
                .replace("\n", "")
                .take(LocationConfig.MAX_DNS_SERVER_LENGTH)
        )
        validateDnsServer(editingConfig.dnsServer)
    }

    private fun validateName(name: String) {
        nameError = when {
            name.isBlank() -> "Name cannot be empty"
            name.length > 30 -> "Name is too long (max 30 chars)"
            else -> null
        }
    }

    private fun validateServer(server: String) {
        val roomLabel = if (editingConfig.bypassProvider == LocationConfig.PROVIDER_JITSI) {
            "Room URL"
        } else {
            "Room ID"
        }
        serverError = when {
            server.isBlank() -> "$roomLabel cannot be empty"
            server.length > 256 -> "$roomLabel is too long"
            else -> null
        }
    }

    private fun validateKey(key: String) {
        keyError = when {
            key.isBlank() -> "Key cannot be empty"
            !LocationConfig.isValidCryptoKey(key) -> "Key must be 64 hex characters"
            else -> null
        }
    }

    private fun validateDnsServer(dnsServer: String) {
        dnsError = if (LocationConfig.isValidDnsServer(dnsServer)) {
            null
        } else {
            "Use host:port or [IPv6]:port; leave empty for Auto"
        }
    }

    private fun editingExternalProfile(): VpnProfileConfig {
        if (isEditingOpenFlux) {
            return editingProfile.copy(
                name = editingName,
                rawConfig = editingOpenFluxConfig.toJson(),
                uri = null,
                localSocksHost = null,
                localSocksPort = null
            ).normalized()
        }
        return editingProfile.copy(
            name = editingName,
            localSocksPort = editingLocalSocksPort.toIntOrNull()
        ).normalized()
    }

    private fun validateExternalProfile() {
        if (isEditingOpenFlux) {
            openFluxEncryptionKeyError = when {
                editingOpenFluxConfig.encryptionKey.isBlank() -> "Key cannot be empty"
                !LocationConfig.isValidCryptoKey(editingOpenFluxConfig.encryptionKey) -> "Key must be 64 hex characters"
                else -> null
            }
            openFluxDocumentUrlError = when {
                editingOpenFluxConfig.documentUrl.isBlank() -> "Document URL cannot be empty"
                openFluxTransportError == null && openFluxEncryptionKeyError == null && !editingOpenFluxConfig.isValid() ->
                    "Use an HTTPS Yandex Docs or Yandex Disk document URL"
                else -> null
            }
            profileError = openFluxTransportError ?: openFluxDocumentUrlError ?: openFluxEncryptionKeyError
            return
        }
        val profile = editingExternalProfile()
        profileError = if (profile.isCompleteFor()) {
            null
        } else {
            when (profile.normalizedType) {
                VpnProfileConfig.TYPE_VLESS -> "Enter a VLESS URI, raw configuration, or local SOCKS port"
                VpnProfileConfig.TYPE_AMNEZIA_WG -> "Enter an AWG URI or raw AmneziaWG configuration"
                VpnProfileConfig.TYPE_AMNEZIA_VPN -> "Enter an Amnezia URI or raw configuration"
                else -> "Enter a profile URI, raw configuration, or local SOCKS port"
            }
        }
    }

    fun saveEditing(onComplete: () -> Unit) {
        validateName(editingName)
        if (isEditingOlcRtc) {
            validateServer(editingConfig.id)
            validateKey(editingConfig.key)
            validateDnsServer(editingConfig.dnsServer)
        } else if (isEditingOpenFlux) {
            validateExternalProfile()
        } else {
            onLocalSocksPortChanged(editingLocalSocksPort)
            validateExternalProfile()
        }

        if (!isFormValid || isSaving) return

        viewModelScope.launch {
            isSaving = true
            try {
                val id = editingId ?: "custom_${(100..999).random()}"
                if (isEditingOlcRtc) {
                    val finalConfig = editingConfig.copy(name = editingName).normalized()
                    locationsRepository.saveLocation(id, finalConfig)
                } else {
                    locationsRepository.saveProfile(id, editingExternalProfile())
                }
                locationsRepository.setActiveLocationId(id)

                loadLocations()

                delay(600)

                onComplete()
            } finally {
                isSaving = false
            }
        }
    }

    fun moveLocation(id: String, offset: Int) {
        if (offset == 0) return
        val current = locations.firstOrNull { it.storageId == id } ?: return
        val reorderGroup = current.reorderGroupKey()
        val peers = locations.filter { it.reorderGroupKey() == reorderGroup }
        val currentIndex = peers.indexOfFirst { it.storageId == id }
        val target = peers.getOrNull(currentIndex + offset) ?: return
        val sourceListIndex = locations.indexOfFirst { it.storageId == current.storageId }
        val targetListIndex = locations.indexOfFirst { it.storageId == target.storageId }
        if (sourceListIndex < 0 || targetListIndex < 0) return

        locations[sourceListIndex] = target
        locations[targetListIndex] = current
        val previousMoveJob = moveLocationJob
        val requestId = ++moveLocationRequest
        moveLocationJob = viewModelScope.launch {
            previousMoveJob?.join()
            runCatching {
                locationsRepository.moveLocation(id, target.storageId)
            }
            if (requestId == moveLocationRequest) {
                loadLocations()
            }
        }
    }

    fun deleteLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.deleteLocation(id)
            loadLocations(onComplete)
        }
    }

    private companion object {
        const val LOCATION_PING_ATTEMPTS = 1
        const val LOCATION_PING_TIMEOUT_MS = 12_000L
        const val LOCATION_PING_RETRY_DELAY_MS = 0L
        const val LOCATION_PING_PARALLELISM = 4
    }

    private data class ProviderDraft(
        val room: String = "",
        val key: String = ""
    )
}
