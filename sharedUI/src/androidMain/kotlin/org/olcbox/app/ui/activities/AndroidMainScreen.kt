package org.olcbox.app.ui.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.data.share.FriendAccessPackageCodec
import org.olcbox.app.data.share.FriendAccessPackageSecurity
import org.olcbox.app.update.AndroidUpdateSettingsStore
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.AndroidUpdateInstaller
import org.olcbox.app.update.UpstreamReleaseInfo
import org.olcbox.app.update.UpstreamUpdateService
import org.olcbox.app.update.hasSeen
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.update.updateStatusMessage
import org.olcbox.app.update.withSeen
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.components.UpstreamUpdateNoticeSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.provisioning.FriendAccessPackageCreatorDialog
import org.olcbox.app.ui.provisioning.FriendAccessPackageInstallDialog
import org.olcbox.app.ui.provisioning.SelfHostedSetupDialog
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelProfile
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.service.OlcboxVpnState

@Composable
fun AndroidMainScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    vpnManager: AndroidVpnManager,
    appUpdateService: AppUpdateService? = null,
    upstreamUpdateService: UpstreamUpdateService? = null
) {

    var currentScreenRoute by rememberSaveable { mutableStateOf("home") }
    var currentLocationId by rememberSaveable { mutableStateOf<String?>(null) }

    val currentScreen: AppScreen =
        when (currentScreenRoute) {
            "location_settings" -> AppScreen.LocationSettings(currentLocationId)
            else -> AppScreen.Home
        }

    val navigate: (AppScreen) -> Unit = { screen ->
        when (screen) {
            AppScreen.Home -> {
                currentScreenRoute = "home"
                currentLocationId = null
            }
            is AppScreen.LocationSettings -> {
                currentScreenRoute = "location_settings"
                currentLocationId = screen.locationId
            }
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionMode by vpnManager.connectionMode.collectAsState()
    val proxySettings by vpnManager.proxySettings.collectAsState()
    val splitTunnelProfile by vpnManager.splitTunnelProfile.collectAsState()
    val splitTunnelSettings by vpnManager.splitTunnelSettings.collectAsState()
    val dynamicThemeEnabled by vpnManager.dynamicThemeEnabled.collectAsState()
    val installedApps by vpnManager.installedApps.collectAsState()
    val homeState by viewModel.state.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pendingLogSaveCallbacks = remember {
        mutableStateOf<Pair<(String) -> Unit, (String) -> Unit>?>(null)
    }
    val pendingVpnAction = remember {
        mutableStateOf<PendingVpnPermissionAction?>(null)
    }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    var appSettingsInitialRoute by remember { mutableStateOf(AppSettingsInitialRoute.Hub) }
    var shareSheetPayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var splitTunnelRestartPending by remember { mutableStateOf(false) }
    var isSelfHostedSetupOpen by remember { mutableStateOf(false) }
    var isFriendPackageCreatorOpen by remember { mutableStateOf(false) }
    var pendingEncryptedFriendPackage by remember { mutableStateOf<String?>(null) }
    val updateSettingsStore = remember(context) {
        AndroidUpdateSettingsStore(context)
    }
    val updateInstaller = remember(context, vpnManager) {
        AndroidUpdateInstaller(context) {
            vpnManager.subscriptionFetchProxy()
        }
    }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var upstreamNotices by remember { mutableStateOf<List<UpstreamReleaseInfo>>(emptyList()) }
    var relaunchAfterInstall by remember { mutableStateOf(false) }
    val subscriptionShareItems = locationViewModel.locations.toList()
        .mapNotNull { item ->
            val url = item.subscriptionUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            url to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { (url, items) ->
            val metadata = items.firstNotNullOfOrNull { it.metadata?.subscription }
            org.olcbox.app.data.share.SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: items.first().fullName,
                updateIntervalMs = metadata?.effectiveUpdateIntervalMs(),
                sourceUpdateIntervalMs = metadata?.updateIntervalMs,
                manualUpdateIntervalMs = metadata?.manualUpdateIntervalMs,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                nextRefreshAtEpochMs = metadata?.nextRefreshAtEpochMs(),
                locationCount = items.size
            )
        }

    val updateInstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (relaunchAfterInstall && result.resultCode == Activity.RESULT_OK) {
            relaunchAfterInstall = false
            updateInstaller.relaunchIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        } else {
            relaunchAfterInstall = false
        }
    }

    LaunchedEffect(homeState.activeProfile?.normalizedType) {
        vpnManager.selectSplitTunnelProfile(
            AndroidSplitTunnelProfile.fromProfileType(homeState.activeProfile?.normalizedType)
        )
    }

    fun markSplitTunnelChanged(changedProfile: AndroidSplitTunnelProfile) {
        val activeProfile = AndroidSplitTunnelProfile.fromProfileType(
            homeState.activeProfile?.normalizedType
        )
        if (changedProfile == activeProfile &&
            homeState.isVpnConnected &&
            connectionMode == AndroidConnectionMode.Tun
        ) {
            splitTunnelRestartPending = true
        }
    }

    fun applyPendingSplitTunnelRestart() {
        if (splitTunnelRestartPending && homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Tun) {
            viewModel.restartVpnIfRunning()
        }
        splitTunnelRestartPending = false
    }

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        updateSettingsStore.save(normalized)
    }

    fun showUpdateResult(info: AppUpdateInfo, settings: AppUpdateSettings): String {
        updateOffer = info.takeIf { it.isUpdateAvailable && !it.isDownloaded(settings) }
        return info.updateStatusMessage(settings)
    }

    fun checkUpdate(manual: Boolean) {
        val service = appUpdateService
        val upstreamService = upstreamUpdateService
        if (service == null && upstreamService == null) {
            updateStatusText = "Update service unavailable"
            return
        }
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateStatusText = "Checking Unified VPN and upstream releases..."
            val proxy = vpnManager.subscriptionFetchProxy()
            val result = service?.check(previousSettings.channel, proxy)
            val upstreamResults = upstreamService?.checkAll(proxy).orEmpty()
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            val statusParts = mutableListOf<String>()
            result?.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        statusParts += showUpdateResult(info, checkedSettings)
                    } else {
                        updateOffer = null
                    }
                },
                onFailure = { error ->
                    statusParts += error.message ?: "Unified VPN update check failed"
                }
            )

            val upstreamInfos = upstreamResults.mapNotNull { it.getOrNull() }
            val unseen = upstreamInfos.filterNot(previousSettings::hasSeen)
            if (unseen.isNotEmpty()) {
                upstreamNotices = (upstreamNotices + unseen).distinctBy { it.identity() }
                statusParts += unseen.joinToString { "${it.project.displayName} updated on GitHub" }
            } else if (manual && upstreamInfos.isNotEmpty()) {
                statusParts += "Original olcbox and Amnezia VPN GitHub versions are up to date"
            }
            if (manual) {
                upstreamResults.mapNotNull { it.exceptionOrNull()?.message }.forEach(statusParts::add)
            }
            updateStatusText = statusParts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            if (!updateInstaller.canRequestPackageInstalls()) {
                updateInstaller.openUnknownSourcesSettings()
                updateStatusText = "Allow Unified VPN to install updates, then tap Download again"
                Toast.makeText(context, updateStatusText, Toast.LENGTH_LONG).show()
                return@launch
            }

            updateDownloadProgress = 0f
            updateStatusText = "Downloading ${info.asset.name}..."
            val result = updateInstaller.download(info.asset) { progress ->
                updateDownloadProgress = progress
            }
            val file = result.getOrElse { error ->
                updateStatusText = "Download failed: ${error.message ?: "unknown error"}"
                updateDownloadProgress = null
                Toast.makeText(context, updateStatusText, Toast.LENGTH_LONG).show()
                return@launch
            }
            updateStatusText = "Installing ${info.asset.name}"
            saveUpdateSettings(
                updateSettings.copy(
                    lastSeenUpdateVersion = info.identity(),
                    lastDownloadedUpdateVersion = info.identity()
                )
            )
            updateOffer = null
            updateDownloadProgress = null
            relaunchAfterInstall = true
            updateInstallLauncher.launch(updateInstaller.installIntent(file))
        }
    }

    fun postponeUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    fun dismissUpstreamNotice(info: UpstreamReleaseInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.withSeen(info))
            upstreamNotices = upstreamNotices.filterNot { it.identity() == info.identity() }
        }
    }

    fun openUpstreamRelease(info: UpstreamReleaseInfo) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
        }.onFailure {
            Toast.makeText(context, "Unable to open GitHub", Toast.LENGTH_SHORT).show()
        }
        dismissUpstreamNotice(info)
    }

    LaunchedEffect(appUpdateService, upstreamUpdateService) {
        val loaded = updateSettingsStore.load()
        updateSettings = loaded
        if (appUpdateService != null || upstreamUpdateService != null) {
            checkUpdate(manual = false)
        }
    }

    fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
        locationViewModel.loadLocations {
            viewModel.loadCurrentConfig(onComplete)
        }
    }

    fun importOrOpenFriendPackage(
        rawText: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (FriendAccessPackageSecurity.isEncryptedPackage(rawText)) {
            pendingEncryptedFriendPackage = rawText.trim()
            return
        }
        viewModel.onImportFullConfig(
            rawText = rawText,
            onComplete = onComplete,
            onError = onError
        )
    }

    val vpnRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (val action = pendingVpnAction.value) {
                PendingVpnPermissionAction.Toggle -> viewModel.ToggleVpn()
                is PendingVpnPermissionAction.RestartWithMode -> {
                    vpnManager.selectConnectionMode(action.mode)
                    viewModel.restartVpnIfRunning()
                }
                null -> Unit
            }
        }
        pendingVpnAction.value = null
    }

    fun connectAfterProfileImport() {
        if (homeState.isVpnConnected) {
            viewModel.restartVpnIfRunning()
            return
        }
        val prepIntent = if (connectionMode == AndroidConnectionMode.Tun) {
            VpnService.prepare(context)
        } else {
            null
        }
        if (prepIntent != null) {
            pendingVpnAction.value = PendingVpnPermissionAction.Toggle
            vpnRequestLauncher.launch(prepIntent)
        } else {
            viewModel.ToggleVpn()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            OlcboxVpnState.addLog("Config file selection canceled")
        } else {
            OlcboxVpnState.addLog("Config file selected")
            viewModel.readImportTextFromSource(
                source = uri,
                onText = { text ->
                    OlcboxVpnState.addLog("Config file read (${text.length} characters)")
                    importOrOpenFriendPackage(
                        rawText = text,
                        onComplete = {
                            reloadLocationsAfterImport {
                                OlcboxVpnState.addLog("Configuration imported")
                                Toast.makeText(context, "Configuration imported", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { message ->
                            OlcboxVpnState.addLog("Config import failed: $message")
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onError = { message ->
                    OlcboxVpnState.addLog("Config file read failed: $message")
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val rawText = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
            ?.trim()
            .orEmpty()

        if (rawText.isBlank()) return@rememberLauncherForActivityResult

        importOrOpenFriendPackage(
            rawText = rawText,
            onComplete = {
                reloadLocationsAfterImport {
                    Toast.makeText(context, "QR imported", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        )
    }

    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val callbacks = pendingLogSaveCallbacks.value
        pendingLogSaveCallbacks.value = null
        if (uri == null || callbacks == null) return@rememberLauncherForActivityResult

        viewModel.onSaveLogsToFile(
            target = uri,
            onSaved = callbacks.first,
            onError = callbacks.second
        )
    }

    fun navigateHomeFromLocationSettings() {
        viewModel.loadCurrentConfig()
        navigate(AppScreen.Home)
    }

    BackHandler(enabled = currentScreen is AppScreen.LocationSettings) {
        navigateHomeFromLocationSettings()
    }

    OlcboxAppContent(
        homeViewModel = viewModel,
        locationViewModel = locationViewModel,
        currentScreen = currentScreen,
        onNavigate = navigate,
        onToggleClick = {
            val prepIntent = if (connectionMode == AndroidConnectionMode.Tun) {
                VpnService.prepare(context)
            } else {
                null
            }
            if (prepIntent != null) {
                pendingVpnAction.value = PendingVpnPermissionAction.Toggle
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        },
        onImportFileRequested = {
            filePickerLauncher.launch(arrayOf("*/*"))
        },
        onImportFromClipboardRequested = { onImported, onError ->
            viewModel.readImportTextFromClipboard(
                onText = { text ->
                    importOrOpenFriendPackage(
                        rawText = text,
                        onComplete = { reloadLocationsAfterImport(onImported) },
                        onError = onError
                    )
                },
                onError = onError
            )
        },
        onScanQrRequested = {
            qrScannerLauncher.launch(Intent(context, QrScannerActivity::class.java))
        },
        onCopyConfigRequested = {
            viewModel.onCopyFullConfigClicked()
        },
        onShareLocationRequested = { config ->
            shareSheetPayload = "Location QR" to ConfigShareService.olcRtcUri(config)
        },
        onSaveLogsRequested = { onSaved, onError ->
            pendingLogSaveCallbacks.value = onSaved to onError
            logSaveLauncher.launch(viewModel.suggestedLogsFileName())
        },
        showAppSettingsButton = true,
        showSplitTunnelingButton = true,
        canScanQr = true,
        onSelfHostedRequested = {
            isSelfHostedSetupOpen = true
        },
        onAppSettingsClick = {
            appSettingsInitialRoute = AppSettingsInitialRoute.Hub
            vpnManager.refreshInstalledApps()
            isAppSettingsOpen = true
        },
        onSplitTunnelingClick = {
            appSettingsInitialRoute = AppSettingsInitialRoute.SplitTunneling
            vpnManager.refreshInstalledApps()
            isAppSettingsOpen = true
        }
    )

    if (isFriendPackageCreatorOpen) {
        FriendAccessPackageCreatorDialog(
            onDismiss = { isFriendPackageCreatorOpen = false },
            onVerified = { vlessUri, server, packagePassword ->
                viewModel.onCreateFriendAccessPackage(
                    vlessUri = vlessUri,
                    amnezia = server,
                    onCreated = { plainPackage ->
                        scope.launch {
                            try {
                                val encrypted = withContext(Dispatchers.Default) {
                                    FriendAccessPackageSecurity.encrypt(plainPackage, packagePassword)
                                }
                                isFriendPackageCreatorOpen = false
                                shareSheetPayload = "Encrypted friend package" to encrypted
                            } catch (failure: Exception) {
                                Toast.makeText(
                                    context,
                                    failure.message ?: "Could not encrypt friend package",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                )
            }
        )
    }

    pendingEncryptedFriendPackage?.let { encryptedPackage ->
        FriendAccessPackageInstallDialog(
            encryptedPackage = encryptedPackage,
            onDismiss = { pendingEncryptedFriendPackage = null },
            onProvisioned = { packageValue, awgConfig ->
                val profiles = FriendAccessPackageCodec.profilesImportText(packageValue)
                viewModel.onImportFullConfig(
                    rawText = profiles,
                    onComplete = {
                        viewModel.onImportFullConfig(
                            rawText = awgConfig,
                            onComplete = {
                                reloadLocationsAfterImport {
                                    pendingEncryptedFriendPackage = null
                                    Toast.makeText(
                                        context,
                                        "olcRTC, VLESS, and AmneziaWG are ready",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    connectAfterProfileImport()
                                }
                            },
                            onError = { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                )
            }
        )
    }

    if (isSelfHostedSetupOpen) {
        SelfHostedSetupDialog(
            onDismiss = { isSelfHostedSetupOpen = false },
            onProvisioned = { config ->
                viewModel.onImportFullConfig(
                    rawText = config,
                    onComplete = {
                        reloadLocationsAfterImport {
                            isSelfHostedSetupOpen = false
                            Toast.makeText(context, "Self-hosted AmneziaWG is ready", Toast.LENGTH_LONG).show()
                            connectAfterProfileImport()
                        }
                    },
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }

    shareSheetPayload?.let { (title, payload) ->
        AndroidConfigShareSheet(
            title = title,
            payload = payload,
            onDismiss = { shareSheetPayload = null }
        )
    }

    updateOffer?.let { info ->
        ApplicationUpdateOfferSheet(
            info = info,
            downloadProgress = updateDownloadProgress,
            onLater = { postponeUpdate(info) },
            onDownload = { downloadUpdate(info) }
        )
    }

    if (updateOffer == null) {
        upstreamNotices.firstOrNull()?.let { info ->
            UpstreamUpdateNoticeSheet(
                info = info,
                onDismiss = { dismissUpstreamNotice(info) },
                onOpenGitHub = { openUpstreamRelease(info) }
            )
        }
    }

    if (isAppSettingsOpen) {
        AppSettingsSheet(
            initialRoute = appSettingsInitialRoute,
            selectedMode = connectionMode,
            proxySettings = proxySettings,
            splitTunnelSettings = splitTunnelSettings,
            splitTunnelProfile = splitTunnelProfile,
            installedApps = installedApps,
            logs = logs,
            dynamicThemeEnabled = dynamicThemeEnabled,
            updateSettings = updateSettings,
            updateStatusText = updateStatusText,
            updateDownloadProgress = updateDownloadProgress,
            subscriptions = subscriptionShareItems,
            enabled = !homeState.isVpnLoading,
            isConnectionActive = homeState.isVpnConnected,
            onDismiss = {
                isAppSettingsOpen = false
                applyPendingSplitTunnelRestart()
            },
            onCopyConfigClick = {
                viewModel.onCopyFullConfigClicked()
                Toast.makeText(context, "Config copied", Toast.LENGTH_SHORT).show()
            },
            onCreateFriendPackageClick = {
                isAppSettingsOpen = false
                isFriendPackageCreatorOpen = true
            },
            onSaveLogsClick = {
                val showToast: (String) -> Unit = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                pendingLogSaveCallbacks.value = showToast to showToast
                logSaveLauncher.launch(viewModel.suggestedLogsFileName())
            },
            onShareLogsClick = {
                val showToast: (String) -> Unit = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                viewModel.onShareLogs(showToast, showToast)
            },
            onUpdateIntervalSelected = { hours ->
                scope.launch {
                    saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                }
            },
            onCheckUpdatesClick = {
                checkUpdate(manual = true)
            },
            onSubscriptionShareClick = { url ->
                shareSheetPayload = "Subscription QR" to ConfigShareService.subscriptionQrText(url)
            },
            onSubscriptionRefreshClick = { url, onFinished ->
                viewModel.refreshSubscription(url) { updatedCount ->
                    reloadLocationsAfterImport {
                        viewModel.restartVpnIfRunning()
                        Toast.makeText(
                            context,
                            if (updatedCount > 0) "Subscription updated" else "Subscription not updated",
                            Toast.LENGTH_SHORT
                        ).show()
                        onFinished()
                    }
                }
            },
            onSubscriptionRefreshIntervalChanged = { url, intervalMs ->
                viewModel.setSubscriptionRefreshInterval(url, intervalMs) {
                    locationViewModel.loadLocations {
                        Toast.makeText(
                            context,
                            if (intervalMs == null) {
                                "Subscription refresh set to Auto"
                            } else {
                                "Subscription refresh rate saved"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onSubscriptionDeleteClick = { url ->
                viewModel.deleteSubscription(url) { removedLocations ->
                    reloadLocationsAfterImport {
                        viewModel.restartVpnIfRunning()
                        Toast.makeText(
                            context,
                            "Subscription deleted · $removedLocations locations removed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDynamicThemeChanged = vpnManager::setDynamicThemeEnabled,
            onModeSelected = { mode ->
                if (mode != connectionMode && homeState.isVpnConnected) {
                    val prepIntent = if (mode == AndroidConnectionMode.Tun) {
                        VpnService.prepare(context)
                    } else {
                        null
                    }
                    if (prepIntent != null) {
                        pendingVpnAction.value = PendingVpnPermissionAction.RestartWithMode(mode)
                        vpnRequestLauncher.launch(prepIntent)
                    } else {
                        vpnManager.selectConnectionMode(mode)
                        viewModel.restartVpnIfRunning()
                    }
                } else if (mode != connectionMode) {
                    vpnManager.selectConnectionMode(mode)
                }
            },
            onProxySettingsSaved = { host, username, password, port ->
                vpnManager.updateProxySettings(host, username, password, port)
                if (homeState.isVpnConnected) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onProxyPasswordRegenerated = {
                vpnManager.regenerateProxyPassword()
                if (homeState.isVpnConnected) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onSplitTunnelProfileSelected = vpnManager::selectSplitTunnelProfile,
            onSplitTunnelModeSelected = { mode: AndroidSplitTunnelMode ->
                vpnManager.selectSplitTunnelMode(mode)
                markSplitTunnelChanged(splitTunnelProfile)
            },
            onSplitTunnelAppToggled = { list: AndroidSplitTunnelList, packageName: String ->
                vpnManager.toggleSplitTunnelApp(list, packageName)
                markSplitTunnelChanged(splitTunnelProfile)
            },
            onSplitTunnelAppsSelected = { list: AndroidSplitTunnelList, packages: Set<String> ->
                vpnManager.setSplitTunnelApps(list, packages)
                markSplitTunnelChanged(splitTunnelProfile)
            }
        )
    }
}

private sealed class PendingVpnPermissionAction {
    object Toggle : PendingVpnPermissionAction()
    data class RestartWithMode(val mode: AndroidConnectionMode) : PendingVpnPermissionAction()
}
