import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI
import java.security.SecureRandom
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.JvmLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.JvmConfigImporter
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.data.share.FriendAccessPackageCodec
import org.olcbox.app.data.share.FriendAccessPackageSecurity
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.ui.components.ApplicationRoutingModeOption
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.components.UpstreamUpdateNoticeSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.provisioning.FriendAccessPackageCreatorDialog
import org.olcbox.app.ui.provisioning.FriendAccessPackageInstallDialog
import org.olcbox.app.ui.provisioning.SelfHostedSetupDialog
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.JvmUpdateInstaller
import org.olcbox.app.update.JvmUpdateSettingsStore
import org.olcbox.app.update.UpstreamReleaseInfo
import org.olcbox.app.update.UpstreamUpdateService
import org.olcbox.app.update.hasSeen
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.update.withSeen
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.DesktopRoutingMode
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.JvmDesktopSocksProxySettingsStore
import org.olcbox.app.vpn.desktop.verifyDesktopNativeAssets

private class DesktopAppDependencies {
    private val closed = AtomicBoolean(false)
    private val locationsDataSource = JvmLocationsDataSourceImpl()
    val configImporter = JvmConfigImporter()

    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val updateService = AppUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val upstreamUpdateService = UpstreamUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val updateSettingsStore = JvmUpdateSettingsStore()
    val updateInstaller = JvmUpdateInstaller()
    val socksProxySettingsStore = JvmDesktopSocksProxySettingsStore()

    val vpnManager = DesktopVpnManager(locationsRepository)

    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = configImporter,
        logExporter = JvmLogExporter()
    )

    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        if (closed.compareAndSet(false, true)) {
            vpnManager.close()
        }
    }
}

private const val WINDOWS_ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"
private const val VERIFY_NATIVE_ASSETS_ARGUMENT = "--verify-native-assets"

fun main(args: Array<String>) {
    if (VERIFY_NATIVE_ASSETS_ARGUMENT in args) {
        val assets = verifyDesktopNativeAssets()
        check(assets.all { it.toFile().isFile && it.toFile().length() > 0L }) {
            "One or more desktop native assets are empty"
        }
        println("Verified ${assets.size} desktop native assets")
        return
    }
    runDesktopApplication(args)
}

private fun runDesktopApplication(args: Array<String>) = application {
    // Configure JNA to find native libraries in resources
    System.setProperty(
        "jna.library.path",
        System.getProperty("jna.library.path", "") +
                File.pathSeparator +
                File(System.getProperty("user.dir"), "native").absolutePath
    )

    val dependencies = remember { DesktopAppDependencies() }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var showDesktopSettings by remember { mutableStateOf(false) }
    var showSelfHostedSetup by remember { mutableStateOf(false) }
    var showFriendPackageCreator by remember { mutableStateOf(false) }
    var pendingEncryptedFriendPackage by remember { mutableStateOf<String?>(null) }
    var isWindowVisible by remember { mutableStateOf(true) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var upstreamNotices by remember { mutableStateOf<List<UpstreamReleaseInfo>>(emptyList()) }
    var sharePayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var desktopNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trayState = rememberTrayState()
    val trayHomeState by dependencies.homeViewModel.state.collectAsState()

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        dependencies.updateSettingsStore.save(normalized)
    }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateMessage = "Checking Unified VPN and upstream releases..."
            val proxy = dependencies.vpnManager.subscriptionFetchProxy()
            val result = dependencies.updateService.check(previousSettings.channel, proxy)
            val upstreamResults = dependencies.upstreamUpdateService.checkAll(proxy)
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            val statusParts = mutableListOf<String>()
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        if (info.isDownloaded(checkedSettings)) {
                            updateOffer = null
                            statusParts += "Unified VPN ${info.version} is already downloaded"
                        } else if (info.isUpdateAvailable) {
                            updateOffer = info
                            statusParts += "Unified VPN update available: ${info.version}"
                        } else {
                            updateOffer = null
                            statusParts += "Unified VPN is up to date"
                        }
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
            updateMessage = statusParts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
    }

    fun quitDesktopApplication() {
        try {
            dependencies.close()
        } finally {
            exitApplication()
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            updateProgress = 0f
            updateMessage = "Downloading ${info.asset.name}..."
            val result = dependencies.updateInstaller.downloadAndOpen(
                info = info,
                proxy = dependencies.vpnManager.subscriptionFetchProxy()
            ) { progress ->
                updateProgress = progress
            }
            val launchResult = result.getOrNull()
            updateMessage = launchResult?.message ?: result.exceptionOrNull()?.let { error ->
                "Download failed: ${error.message ?: "unknown error"}"
            }
            if (result.isSuccess) {
                saveUpdateSettings(
                    updateSettings.copy(
                        lastSeenUpdateVersion = info.identity(),
                        lastDownloadedUpdateVersion = info.identity()
                    )
                )
                updateOffer = null
            }
            updateProgress = null
            if (launchResult?.shouldExitApplication == true) {
                delay(500)
                quitDesktopApplication()
            }
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
            check(Desktop.isDesktopSupported())
            Desktop.getDesktop().browse(URI(info.htmlUrl))
        }.onFailure {
            desktopNotice = "Unable to open GitHub"
        }
        dismissUpstreamNotice(info)
    }

    LaunchedEffect(Unit) {
        val loaded = dependencies.updateSettingsStore.load()
        updateSettings = loaded
        dependencies.vpnManager.updateSocksProxySettings(dependencies.socksProxySettingsStore.load())
        checkUpdate(manual = false)
        if (WINDOWS_ELEVATED_START_ARGUMENT in args) {
            dependencies.homeViewModel.loadCurrentConfig {
                dependencies.homeViewModel.ToggleVpn()
            }
        }
    }

    LaunchedEffect(desktopNotice) {
        if (desktopNotice != null) {
            delay(1_800)
            desktopNotice = null
        }
    }

    Tray(
        state = trayState,
        icon = painterResource("LinuxIcon.png"),
        tooltip = "Unified VPN",
        menu = {
		Item(
			text = "Open Unified VPN window",
			onClick = {
				isWindowVisible = true
			}
		)

		Separator()

		Item(
			text = if (trayHomeState.isVpnConnected || trayHomeState.isVpnLoading)
				"Stop relay"
			else
				"Start relay",
			enabled = trayHomeState.isVpnConnected ||
				trayHomeState.isVpnLoading ||
				trayHomeState.canStartVpn,
			onClick = {
				dependencies.homeViewModel.ToggleVpn()
			}
		)

		Separator()

		Item(
			text = "Connection settings",
			onClick = {
				isWindowVisible = true
				showDesktopSettings = true
			}
		)

		Separator()

		Item(
			text = "Quit Unified VPN",
			onClick = ::quitDesktopApplication
		)
	}
    )

    Window(
        title = "Unified VPN",
        icon = painterResource("LinuxIcon.png"),
        visible = isWindowVisible,
        state = rememberWindowState(width = 430.dp, height = 780.dp),
        onCloseRequest = ::quitDesktopApplication,
    ) {
        window.minimumSize = Dimension(350, 600)

        DisposableEffect(Unit) {
            onDispose {
                dependencies.close()
            }
        }

        AppTheme {
            val logs by dependencies.homeViewModel.logs.collectAsState()
            val homeState by dependencies.homeViewModel.state.collectAsState()
            val socksProxySettings by dependencies.vpnManager.socksProxySettings.collectAsState()

            fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
                dependencies.locationViewModel.loadLocations {
                    dependencies.homeViewModel.loadCurrentConfig(onComplete)
                }
            }

            fun importOrOpenFriendPackage(
                rawText: String,
                onComplete: () -> Unit = {},
                onError: (String) -> Unit = { desktopNotice = it }
            ) {
                if (FriendAccessPackageSecurity.isEncryptedPackage(rawText)) {
                    pendingEncryptedFriendPackage = rawText.trim()
                    return
                }
                dependencies.homeViewModel.onImportFullConfig(
                    rawText = rawText,
                    onComplete = onComplete,
                    onError = onError
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                OlcboxAppContent(
                    homeViewModel = dependencies.homeViewModel,
                    locationViewModel = dependencies.locationViewModel,
                    currentScreen = currentScreen,
                    onNavigate = { screen ->
                        currentScreen = screen
                    },
                    onToggleClick = {
                        dependencies.homeViewModel.ToggleVpn()
                    },
                    onImportFileRequested = {
                        chooseConfigFile(window)?.let { file ->
                            dependencies.homeViewModel.readImportTextFromSource(
                                source = file,
                                onText = { text ->
                                    importOrOpenFriendPackage(
                                        rawText = text,
                                        onComplete = { reloadLocationsAfterImport() }
                                    )
                                },
                                onError = { desktopNotice = it }
                            )
                        }
                    },
                    onImportFromClipboardRequested = { onImported, onError ->
                        dependencies.homeViewModel.readImportTextFromClipboard(
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
                    onScanQrRequested = {},
                    onCopyConfigRequested = {
                        dependencies.homeViewModel.onCopyFullConfigClicked()
                    },
                    onShareLocationRequested = { config ->
                        sharePayload = "Location QR" to ConfigShareService.olcRtcUri(config)
                    },
                    onSaveLogsRequested = { onSaved, onError ->
                        chooseSaveFile(
                            owner = window,
                            defaultName = dependencies.homeViewModel.suggestedLogsFileName()
                        )?.let { file ->
                            dependencies.homeViewModel.onSaveLogsToFile(
                                target = file,
                                onSaved = onSaved,
                                onError = onError
                            )
                        }
                    },
                    showAppSettingsButton = true,
                    showSplitTunnelingButton = false,
                    canScanQr = false,
                    onSelfHostedRequested = { showSelfHostedSetup = true },
                    onAppSettingsClick = { showDesktopSettings = true },
                    onSplitTunnelingClick = {}
                )

                if (showDesktopSettings) {
                    ApplicationSettingsSheet(
                        updateSettings = updateSettings,
                        updateStatusText = updateMessage,
                        updateOffer = upstreamNotices.firstOrNull(),
                        subscriptions = desktopSubscriptionItems(dependencies.locationViewModel.locations.toList()),
                        logs = logs,
                        connectionSummary = "${socksProxySettings.routingMode.effectiveDisplayName()} · " +
                            "SOCKS5 ${socksProxySettings.host}:${socksProxySettings.port}",
                        connectionDetails = buildList {
                            add("Mode" to socksProxySettings.routingMode.effectiveDisplayName())
                            if (socksProxySettings.routingMode.effectiveMode() == DesktopRoutingMode.SystemProxy) {
                                add("PAC URL" to "http://127.0.0.1:10809/proxy.pac")
                                add("PAC Target" to "SOCKS5 ${socksProxySettings.host}:${socksProxySettings.port}")
                            }
                        },
                        socksProxySettings = socksProxySettings.toApplicationSocksProxySettings(),
                        routingModeOptions = DesktopRoutingMode.availableForCurrentPlatform().map { mode ->
                            ApplicationRoutingModeOption(
                                id = mode.name,
                                title = mode.displayName(),
                                subtitle = mode.description()
                            )
                        },
                        selectedRoutingModeId = socksProxySettings.routingMode.name,
                        isConnectionActive = homeState.isVpnConnected,
                        onDismiss = { showDesktopSettings = false },
                        onCopyConfigClick = {
                            dependencies.homeViewModel.onCopyFullConfigClicked()
                            desktopNotice = "Copied"
                        },
                        onCreateFriendPackageClick = {
                            showDesktopSettings = false
                            showFriendPackageCreator = true
                        },
                        onSaveLogsClick = {
                            chooseSaveFile(
                                owner = window,
                                defaultName = dependencies.homeViewModel.suggestedLogsFileName()
                            )?.let { file ->
                                dependencies.homeViewModel.onSaveLogsToFile(
                                    target = file,
                                    onSaved = { message -> updateMessage = message },
                                    onError = { message -> updateMessage = message }
                                )
                            }
                        },
                        onShareLogsClick = {
                            dependencies.homeViewModel.onShareLogs(
                                onShared = { message -> updateMessage = message },
                                onError = { message -> updateMessage = message }
                            )
                        },
                        onUpdateIntervalSelected = { hours ->
                            scope.launch {
                                saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                            }
                        },
                        onCheckUpdatesClick = { checkUpdate(manual = true) },
                        onOpenUpstreamReleaseClick = ::openUpstreamRelease,
                        onDismissUpstreamReleaseClick = ::dismissUpstreamNotice,
                        onSubscriptionShareClick = { url ->
                            sharePayload = "Subscription QR" to ConfigShareService.subscriptionQrText(url)
                        },
                        onSubscriptionRefreshClick = { url, onFinished ->
                            dependencies.homeViewModel.refreshSubscription(url) { updatedCount ->
                                reloadLocationsAfterImport {
                                    dependencies.homeViewModel.restartVpnIfRunning()
                                    updateMessage = if (updatedCount > 0) {
                                        "Subscription updated"
                                    } else {
                                        "Subscription not updated"
                                    }
                                    onFinished()
                                }
                            }
                        },
                        onSubscriptionRefreshIntervalChanged = { url, intervalMs ->
                            dependencies.homeViewModel.setSubscriptionRefreshInterval(url, intervalMs) {
                                dependencies.locationViewModel.loadLocations()
                                updateMessage = if (intervalMs == null) {
                                    "Subscription refresh set to Auto"
                                } else {
                                    "Subscription refresh rate saved"
                                }
                            }
                        },
                        onSubscriptionDeleteClick = { url ->
                            dependencies.homeViewModel.deleteSubscription(url) { removedLocations ->
                                reloadLocationsAfterImport {
                                    dependencies.homeViewModel.restartVpnIfRunning()
                                    updateMessage =
                                        "Subscription deleted · $removedLocations locations removed"
                                }
                            }
                        },
                        onSocksProxySettingsSaved = { username, password, port ->
                            val settings = socksProxySettings.copy(
                                port = port,
                                username = username,
                                password = password
                            ).normalized()
                            dependencies.vpnManager.updateSocksProxySettings(settings)
                            scope.launch {
                                dependencies.socksProxySettingsStore.save(settings)
                            }
                            desktopNotice = "SOCKS proxy saved"
                            if (homeState.isVpnConnected) {
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        },
                        onSocksProxyPasswordRegenerated = {
                            val settings = socksProxySettings.copy(
                                password = generateDesktopProxyPassword()
                            ).normalized()
                            dependencies.vpnManager.updateSocksProxySettings(settings)
                            scope.launch {
                                dependencies.socksProxySettingsStore.save(settings)
                            }
                            desktopNotice = "Password regenerated"
                            if (homeState.isVpnConnected) {
                                dependencies.homeViewModel.restartVpnIfRunning()
                            }
                        },
                        onRoutingModeSelected = { id ->
                            val mode = runCatching { DesktopRoutingMode.valueOf(id) }
                                .getOrDefault(DesktopRoutingMode.Auto)
                            if (mode != socksProxySettings.routingMode) {
                                val settings = socksProxySettings.copy(routingMode = mode).normalized()
                                dependencies.vpnManager.updateSocksProxySettings(settings)
                                scope.launch {
                                    dependencies.socksProxySettingsStore.save(settings)
                                }
                                desktopNotice = "Connection mode saved"
                                if (homeState.isVpnConnected) {
                                    dependencies.homeViewModel.restartVpnIfRunning()
                                }
                            }
                        }
                    )
                }

                if (showFriendPackageCreator) {
                    FriendAccessPackageCreatorDialog(
                        onDismiss = { showFriendPackageCreator = false },
                        onVerified = { vlessUri, server, packagePassword ->
                            dependencies.homeViewModel.onCreateFriendAccessPackage(
                                vlessUri = vlessUri,
                                amnezia = server,
                                onCreated = { plainPackage ->
                                    scope.launch {
                                        try {
                                            val encrypted = withContext(Dispatchers.Default) {
                                                FriendAccessPackageSecurity.encrypt(
                                                    plainPackage,
                                                    packagePassword
                                                )
                                            }
                                            showFriendPackageCreator = false
                                            sharePayload = "Encrypted friend package" to encrypted
                                        } catch (failure: Exception) {
                                            desktopNotice = failure.message
                                                ?: "Could not encrypt friend package"
                                        }
                                    }
                                },
                                onError = { desktopNotice = it }
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
                            dependencies.homeViewModel.onImportFullConfig(
                                rawText = profiles,
                                onComplete = {
                                    dependencies.homeViewModel.onImportFullConfig(
                                        rawText = awgConfig,
                                        onComplete = {
                                            reloadLocationsAfterImport {
                                                pendingEncryptedFriendPackage = null
                                                desktopNotice = "olcRTC, VLESS, and AmneziaWG are ready"
                                                if (homeState.isVpnConnected) {
                                                    dependencies.homeViewModel.restartVpnIfRunning()
                                                } else {
                                                    dependencies.homeViewModel.ToggleVpn()
                                                }
                                            }
                                        },
                                        onError = { desktopNotice = it }
                                    )
                                },
                                onError = { desktopNotice = it }
                            )
                        }
                    )
                }

                if (showSelfHostedSetup) {
                    SelfHostedSetupDialog(
                        onDismiss = { showSelfHostedSetup = false },
                        onProvisioned = { config ->
                            dependencies.homeViewModel.onImportFullConfig(
                                rawText = config,
                                onComplete = {
                                    reloadLocationsAfterImport {
                                        showSelfHostedSetup = false
                                        desktopNotice = "Self-hosted AmneziaWG is ready"
                                        if (homeState.isVpnConnected) {
                                            dependencies.homeViewModel.restartVpnIfRunning()
                                        } else {
                                            dependencies.homeViewModel.ToggleVpn()
                                        }
                                    }
                                },
                                onError = { message -> desktopNotice = message }
                            )
                        }
                    )
                }

                updateOffer?.let { info ->
                    ApplicationUpdateOfferSheet(
                        info = info,
                        downloadProgress = updateProgress,
                        onLater = { postponeUpdate(info) },
                        onDownload = { downloadUpdate(info) }
                    )
                }

                if (updateOffer == null && !showDesktopSettings) {
                    upstreamNotices.firstOrNull()?.let { info ->
                        UpstreamUpdateNoticeSheet(
                            info = info,
                            onDismiss = { dismissUpstreamNotice(info) },
                            onOpenGitHub = { openUpstreamRelease(info) }
                        )
                    }
                }

                sharePayload?.let { (title, payload) ->
                    DesktopConfigShareOverlay(
                        title = title,
                        payload = payload,
                        onCopy = {
                            dependencies.configImporter.copyToClipboard(payload)
                            desktopNotice = "Copied"
                        },
                        onDismiss = {
                            sharePayload = null
                        }
                    )
                }

                desktopNotice?.let { notice ->
                    DesktopNotice(
                        text = notice,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopConfigShareOverlay(
    title: String,
    payload: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var copied by remember(payload) { mutableStateOf(false) }
    val qrMatrix = remember(payload) {
        runCatching {
            MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 128, 128)
        }.getOrNull()
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val noOpInteraction = remember { MutableInteractionSource() }

            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 440.dp)
                    .clickable(
                        interactionSource = noOpInteraction,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (copied) "Copied to clipboard" else "Scan QR or copy the link",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }

                    if (qrMatrix != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(240.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            DesktopQrCode(
                                matrix = qrMatrix,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        SelectionContainer {
                            Text(
                                text = payload,
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onCopy()
                                copied = true
                            }
                        ) {
                            Text("Copy")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopQrCode(
    matrix: BitMatrix,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color.White)
        val cellSize = min(size.width / matrix.width, size.height / matrix.height)
        val qrWidth = cellSize * matrix.width
        val qrHeight = cellSize * matrix.height
        val left = (size.width - qrWidth) / 2f
        val top = (size.height - qrHeight) / 2f

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + x * cellSize, top + y * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNotice(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun DesktopSocksProxySettings.toApplicationSocksProxySettings(): ApplicationSocksProxySettings {
    return ApplicationSocksProxySettings(
        host = host,
        port = port,
        username = username,
        password = password
    )
}

private fun generateDesktopProxyPassword(length: Int = 24): String {
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(DESKTOP_PROXY_PASSWORD_ALPHABET[random.nextInt(DESKTOP_PROXY_PASSWORD_ALPHABET.length)])
        }
    }
}

private const val DESKTOP_PROXY_PASSWORD_ALPHABET =
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

private fun desktopSubscriptionItems(items: List<LocationItem>): List<SubscriptionShareItem> {
    return items
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
        .map { (url, subscriptionItems) ->
            val metadata = subscriptionItems.firstNotNullOfOrNull { it.metadata?.subscription }
            SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: subscriptionItems.first().fullName,
                updateIntervalMs = metadata?.effectiveUpdateIntervalMs(),
                sourceUpdateIntervalMs = metadata?.updateIntervalMs,
                manualUpdateIntervalMs = metadata?.manualUpdateIntervalMs,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                nextRefreshAtEpochMs = metadata?.nextRefreshAtEpochMs(),
                locationCount = subscriptionItems.size
            )
        }
}

private fun chooseConfigFile(owner: Frame): File? {
    val dialog = FileDialog(owner, "Import Unified VPN Config", FileDialog.LOAD)
    dialog.isVisible = true

    return dialog.files.firstOrNull()
}

private fun chooseSaveFile(owner: Frame, defaultName: String): File? {
    val dialog = FileDialog(owner, "Save Unified VPN Logs", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true

    val fileName = dialog.file ?: return null
    val directory = dialog.directory ?: return File(fileName)

    return File(directory, fileName)
}
