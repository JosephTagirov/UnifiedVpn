package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import org.olcbox.app.ui.localization.AppText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    canScanQr: Boolean = false,
    onSelfHostedRequested: (() -> Unit)? = null,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }
    var isManualImportOpen by remember { mutableStateOf(false) }
    var manualImportText by remember { mutableStateOf("") }
    var manualSubscriptionRefresh by remember { mutableStateOf("") }
    var manualSubscriptionAllowInsecure by remember { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    val hasSubscriptions = locations.any { !it.subscriptionUrl.isNullOrBlank() }

    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onForeground()
    }

    val primaryActionLabel = when {
        requiresSetup -> "SETUP"
        state.isVpnLoading || state.isVpnConnected -> "STOP"
        else -> "START"
    }

    fun refreshSubscriptions() {
        viewModel.refreshSubscriptions { updatedCount ->
            locationViewModel.loadLocations {
                viewModel.restartVpnIfRunning()

                val message = if (updatedCount > 0) {
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
            SnackbarHost(snackbarHostState)
        },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                showSplitTunnelingButton = showSplitTunnelingButton,
                onSplitTunnelingClick = onSplitTunnelingClick,
                onAddClick = { isAddSheetOpen = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RelayStatus(
                isActive = state.isVpnConnected,
                requiresSetup = requiresSetup
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

            Spacer(modifier = Modifier.height(16.dp))

            LocationSelectorScreen(
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
