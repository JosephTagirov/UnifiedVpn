package org.olcbox.app.ui.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import org.olcbox.app.ui.components.HomeScrollIndicator
import org.olcbox.app.ui.components.ownerCabinetEnabled
import org.olcbox.app.ui.components.safePortalUrl
import androidx.compose.runtime.SideEffect
import org.olcbox.app.data.logging.SupportLogs
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalUriHandler
import org.olcbox.app.ui.features.home.components.BrandHomeIntro
import org.olcbox.app.ui.features.home.components.BrandHomeNavigation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.size
import org.olcbox.app.ui.localization.AppText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.olcbox.app.data.model.parseSubscriptionRefreshIntervalMs
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.RelayStatus
import org.olcbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    showAddOlcRtcButton: Boolean = false,
    protocolSummary: String = "olcRTC, VLESS, Amnezia",
    canScanQr: Boolean = false,
    onSelfHostedRequested: (() -> Unit)? = null,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddOpenFluxLocation: () -> Unit,
    onAddLocation: () -> Unit
) {
    var isLogsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isAddSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isManualImportOpen by rememberSaveable { mutableStateOf(false) }
    var manualImportText by rememberSaveable { mutableStateOf("") }
    var manualSubscriptionRefresh by rememberSaveable { mutableStateOf("") }
    var manualSubscriptionAllowInsecure by rememberSaveable { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    val hasSubscriptions = locations.any { !it.subscriptionUrl.isNullOrBlank() }

    SideEffect {
        val selected = locations.firstOrNull { it.storageId==locationViewModel.selectedLocationId }
        SupportLogs.selectTarget(selected?.subscriptionUrl ?: locations.firstOrNull { it.subscriptionUrl?.startsWith("https://panel.meesharr.space/vpn-control/")==true }?.subscriptionUrl)
    }
    val uriHandler = LocalUriHandler.current
    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onForeground()
    }

    val primaryActionLabel = when {
        requiresSetup -> "Add profile"
        state.isVpnLoading -> "Cancel connection"
        state.isVpnConnected -> "Disconnect"
        else -> "Connect"
    }

    fun refreshSubscriptions() {
        viewModel.refreshSubscriptions { updatedCount ->
            locationViewModel.loadLocations {
                val message = viewModel.state.value.subscriptionRefreshMessage ?: if (updatedCount > 0) {
                    "Subscriptions updated: $updatedCount"
                } else {
                    "No subscriptions to update"
                }

                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    fun refreshHttpPings(targetLocationIds: List<String>? = null) {
        locationViewModel.refreshPings(
            targetLocationIds = targetLocationIds,
            performPing = { config, profile ->
                viewModel.performPingFor(config, profile)
            },
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar { Text(data.visuals.message) } }
        },
        topBar = {
            HomeScreenAppBar(
                protocolSummary = protocolSummary,
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                showSplitTunnelingButton = showSplitTunnelingButton,
                onSplitTunnelingClick = onSplitTunnelingClick,
                onAddClick = { isAddSheetOpen = true }
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max=780.dp).fillMaxWidth()) {
            BrandHomeIntro()
            RelayStatus(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                requiresSetup = requiresSetup,
                profileCount = locations.size
            )

            Spacer(modifier = Modifier.height(16.dp))

            StartButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                requiresSetup = requiresSetup,
                label = primaryActionLabel,
                enabled = true,
                onClick = {
                    if (requiresSetup) {
                        isAddSheetOpen = true
                    } else {
                        onToggleClick()
                    }
                }
            )

            state.connectionError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            val owner = ownerCabinetEnabled()
            val portal = locations.firstOrNull { it.storageId == locationViewModel.selectedLocationId }
                ?.metadata?.subscription?.safePortalUrl()
                ?: locations.firstNotNullOfOrNull { it.metadata?.subscription?.safePortalUrl() }
            BrandHomeNavigation(showCabinet=owner || portal!=null,owner=owner,onLogs={isLogsSheetOpen=true},onCabinet={
                runCatching { uriHandler.openUri(if(owner) "https://panel.meesharr.space/vpn-control/admin/" else portal!!) }
                    .onFailure { scope.launch { snackbarHostState.showSnackbar("Could not open the browser") } }
            })
            if (hasSubscriptions) {
                OutlinedButton(
                    onClick = { refreshSubscriptions() },
                    enabled = !state.isRefreshingSubscriptions,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    if (state.isRefreshingSubscriptions) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isRefreshingSubscriptions) "Updating subscriptions..." else "Update subscription")
                }
                state.subscriptionRefreshMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = if (state.subscriptionRefreshFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            LocationSelectorScreen(
                showAddOlcRtcButton = showAddOlcRtcButton,
                onRefreshClick = { targetIds ->
                    refreshHttpPings(targetIds)
                },
                onAddSubscriptionClick = {
                    isAddSheetOpen = true
                },
                locations = locations,
                selectedLocationId = locationViewModel.selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = { id ->
                    val restartAfterSelection = viewModel.shouldRestartVpnAfterProfileSelection()
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig {
                            viewModel.restartVpnIfRunning(force = restartAfterSelection)
                        }
                    }
                },
                onLocationSettingsClick = { id ->
                    onOpenLocationSettings(id)
                },
                onMoveLocation = locationViewModel::moveLocation,
                onAddLocationClick = {
                    onAddLocation()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
            }
        }

        HomeScrollIndicator(state=scrollState,modifier=Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp))
        }

        if (isLogsSheetOpen) {
            val logs by viewModel.logs.collectAsState()

            LogsSheet(
                logs = logs,
                onSaveClick = {
                    onSaveLogsRequested(
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onShareClick = {
                    viewModel.onShareLogs(
                        onShared = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onDismiss = {
                    isLogsSheetOpen = false
                }
            )
        }

        if (isAddSheetOpen) {
            AddConfigurationSheet(
                canScanQr = canScanQr,
                hasSubscriptions = hasSubscriptions,
                onDismiss = {
                    isAddSheetOpen = false
                },
                onScanQrClick = {
                    isAddSheetOpen = false
                    onScanQrRequested()
                },
                onPasteLinkClick = {
                    isAddSheetOpen = false
                    isManualImportOpen = true
                },
                onImportFileClick = {
                    isAddSheetOpen = false
                    onImportFileRequested()
                },
                onUpdateSubscriptionsClick = {
                    isAddSheetOpen = false
                    refreshSubscriptions()
                },
                onSelfHostedClick = onSelfHostedRequested?.let { request ->
                    {
                        isAddSheetOpen = false
                        request()
                    }
                },
                onAddOpenFluxClick = {
                    isAddSheetOpen = false
                    onAddOpenFluxLocation()
                },
                onAddCustomLocationClick = {
                    isAddSheetOpen = false
                    onAddLocation()
                }
            )
        }

        if (isManualImportOpen) {
            val normalizedImportText = manualImportText.trim()
            val isSubscriptionUrl = normalizedImportText.startsWith("https://", ignoreCase = true) ||
                normalizedImportText.startsWith("http://", ignoreCase = true)
            val subscriptionRefreshIntervalMs = manualSubscriptionRefresh
                .takeIf { it.isNotBlank() }
                ?.let(::parseSubscriptionRefreshIntervalMs)
            val subscriptionRefreshError = isSubscriptionUrl &&
                manualSubscriptionRefresh.isNotBlank() &&
                subscriptionRefreshIntervalMs == null

            AlertDialog(
                onDismissRequest = {
                    isManualImportOpen = false
                    manualSubscriptionRefresh = ""
                    manualSubscriptionAllowInsecure = false
                },
                title = { Text("Import link or URI") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = manualImportText,
                            onValueChange = { manualImportText = it },
                            label = { Text("HTTP, HTTPS, or olcrtc URI") },
                            placeholder = { Text("https://example.org/subscription") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isSubscriptionUrl) {
                            OutlinedTextField(
                                value = manualSubscriptionRefresh,
                                onValueChange = { value ->
                                    manualSubscriptionRefresh = value
                                        .lowercase()
                                        .filter { it.isDigit() || it in "smhd" }
                                        .take(8)
                                },
                                label = { Text("Subscription refresh rate") },
                                placeholder = { Text("Auto") },
                                supportingText = {
                                    Text(
                                        if (subscriptionRefreshError) {
                                            "Use 5m–30d, for example 10m, 6h, or 1d"
                                        } else {
                                            "Optional. Empty implies default."
                                        }
                                    )
                                },
                                isError = subscriptionRefreshError,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = manualSubscriptionAllowInsecure,
                                    onCheckedChange = { manualSubscriptionAllowInsecure = it }
                                )
                                Text("Allow insecure requests")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = manualImportText.isNotBlank() && !subscriptionRefreshError,
                        onClick = {
                            viewModel.onImportFullConfig(
                                rawText = manualImportText,
                                subscriptionRefreshIntervalMs = subscriptionRefreshIntervalMs,
                                allowInsecureSubscriptionRequests = manualSubscriptionAllowInsecure,
                                onComplete = {
                                    isManualImportOpen = false
                                    manualImportText = ""
                                    manualSubscriptionRefresh = ""
                                    manualSubscriptionAllowInsecure = false
                                    locationViewModel.loadLocations {
                                        viewModel.loadCurrentConfig()
                                    }
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Configuration imported")
                                    }
                                },
                                onError = { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            )
                        }
                    ) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.readImportTextFromClipboard(
                                onText = { text ->
                                    manualImportText = text
                                },
                                { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            )
                        }
                    ) {
                        Text("Paste clipboard")
                    }
                }
            )
        }
    }
}
