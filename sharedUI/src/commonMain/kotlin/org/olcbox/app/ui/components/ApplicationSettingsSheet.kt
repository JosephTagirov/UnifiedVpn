package org.olcbox.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.model.formatSubscriptionRefreshInterval
import org.olcbox.app.data.model.parseSubscriptionRefreshIntervalMs
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.UpstreamReleaseInfo
import kotlin.time.Clock
import kotlin.time.Instant

data class ApplicationSocksProxySettings(
    val host: String = "127.0.0.1",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val password: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 10808
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_CREDENTIAL_LENGTH = 64

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}

data class ApplicationRoutingModeOption(
    val id: String,
    val title: String,
    val subtitle: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationSettingsSheet(
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateOffer: UpstreamReleaseInfo?,
    subscriptions: List<SubscriptionShareItem>,
    logs: List<String>,
    connectionSummary: String,
    connectionDetails: List<Pair<String, String>>,
    socksProxySettings: ApplicationSocksProxySettings? = null,
    routingModeOptions: List<ApplicationRoutingModeOption> = listOf(
        ApplicationRoutingModeOption("proxy", "Proxy", "Local SOCKS endpoint")
    ),
    selectedRoutingModeId: String = routingModeOptions.firstOrNull()?.id.orEmpty(),
    isConnectionActive: Boolean = false,
    onDismiss: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onOpenUpstreamReleaseClick: (UpstreamReleaseInfo) -> Unit,
    onDismissUpstreamReleaseClick: (UpstreamReleaseInfo) -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRefreshClick: (String, () -> Unit) -> Unit,
    onSubscriptionRefreshIntervalChanged: (String, Long?) -> Unit = { _, _ -> },
    onSubscriptionDeleteClick: (String) -> Unit = {},
    onSocksProxySettingsSaved: (String, String, Int) -> Unit = { _, _, _ -> },
    onSocksProxyPasswordRegenerated: () -> Unit = {},
    onRoutingModeSelected: (String) -> Unit = {},
    onCreateFriendPackageClick: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var route by remember { mutableStateOf(SharedSettingsRoute.Hub) }
    var selectedSubscriptionUrl by remember { mutableStateOf<String?>(null) }
    var refreshingSubscriptionUrl by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 180,
                        delayMillis = 60,
                        easing = LinearOutSlowInEasing
                    )
                ).togetherWith(
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 90,
                            easing = FastOutLinearInEasing
                        )
                    )
                ).using(
                    SizeTransform(
                        clip = false,
                        sizeAnimationSpec = { _, _ ->
                            tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            )
                        }
                    )
                )
            },
            label = "sharedApplicationSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                SharedSettingsRoute.Hub -> SharedSettingsHubContent(
                    updateSettings = updateSettings,
                    subscriptionsCount = subscriptions.size,
                    connectionSummary = connectionSummary,
                    onConnectionClick = { route = SharedSettingsRoute.Connection },
                    onSubscriptionsClick = { route = SharedSettingsRoute.Subscriptions },
                    onUpdatesClick = { route = SharedSettingsRoute.Updates },
                    onLogsClick = { route = SharedSettingsRoute.Logs }
                )

                SharedSettingsRoute.Connection -> SharedConnectionSettingsContent(
                    summary = connectionSummary,
                    details = connectionDetails,
                    socksProxySettings = socksProxySettings,
                    routingModeTitle = routingModeOptions
                        .firstOrNull { it.id == selectedRoutingModeId }
                        ?.title
                        ?: "Proxy",
                    onConnectionModeClick = { route = SharedSettingsRoute.ConnectionMode },
                    onSocksProxyClick = { route = SharedSettingsRoute.SocksProxy },
                    onBack = { route = SharedSettingsRoute.Hub }
                )

                SharedSettingsRoute.ConnectionMode -> SharedConnectionModeSettingsContent(
                    options = routingModeOptions,
                    selectedId = selectedRoutingModeId,
                    onSelected = onRoutingModeSelected,
                    onBack = { route = SharedSettingsRoute.Connection }
                )

                SharedSettingsRoute.SocksProxy -> if (socksProxySettings != null) {
                    SharedSocksProxySettingsContent(
                        settings = socksProxySettings,
                        isConnectionActive = isConnectionActive,
                        onBack = { route = SharedSettingsRoute.Connection },
                        onProxySettingsSaved = onSocksProxySettingsSaved,
                        onProxyPasswordRegenerated = onSocksProxyPasswordRegenerated
                    )
                }

                SharedSettingsRoute.Subscriptions -> SharedSubscriptionsSettingsContent(
                    subscriptions = subscriptions,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onCopyConfigClick = onCopyConfigClick,
                    onCreateFriendPackageClick = onCreateFriendPackageClick,
                    onSubscriptionClick = { item ->
                        selectedSubscriptionUrl = item.url
                        route = SharedSettingsRoute.SubscriptionDetails
                    }
                )

                SharedSettingsRoute.SubscriptionDetails -> {
                    val item = subscriptions.firstOrNull { it.url == selectedSubscriptionUrl }
                    if (item == null) {
                        SharedSubscriptionsSettingsContent(
                            subscriptions = subscriptions,
                            onBack = { route = SharedSettingsRoute.Hub },
                            onCopyConfigClick = onCopyConfigClick,
                            onCreateFriendPackageClick = onCreateFriendPackageClick,
                            onSubscriptionClick = { selected ->
                                selectedSubscriptionUrl = selected.url
                                route = SharedSettingsRoute.SubscriptionDetails
                            }
                        )
                    } else {
                        SharedSubscriptionDetailsContent(
                            item = item,
                            isRefreshing = refreshingSubscriptionUrl == item.url,
                            onBack = { route = SharedSettingsRoute.Subscriptions },
                            onShareClick = { onSubscriptionShareClick(item.url) },
                            onRefreshClick = {
                                refreshingSubscriptionUrl = item.url
                                onSubscriptionRefreshClick(item.url) {
                                    refreshingSubscriptionUrl = null
                                }
                            },
                            onRefreshIntervalChanged = { intervalMs ->
                                onSubscriptionRefreshIntervalChanged(item.url, intervalMs)
                            },
                            onDeleteClick = {
                                onSubscriptionDeleteClick(item.url)
                                selectedSubscriptionUrl = null
                                route = SharedSettingsRoute.Subscriptions
                            }
                        )
                    }
                }

                SharedSettingsRoute.Updates -> SharedUpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    offer = updateOffer,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onIntervalSelected = onUpdateIntervalSelected,
                    onCheckUpdatesClick = onCheckUpdatesClick,
                    onOpenUpstreamReleaseClick = onOpenUpstreamReleaseClick,
                    onDismissUpstreamReleaseClick = onDismissUpstreamReleaseClick
                )

                SharedSettingsRoute.Logs -> SharedLogsSettingsContent(
                    logs = logs,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )
            }
        }
    }
}

@Composable
private fun SharedSettingsHubContent(
    updateSettings: AppUpdateSettings,
    subscriptionsCount: Int,
    connectionSummary: String,
    onConnectionClick: () -> Unit,
    onSubscriptionsClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onLogsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SharedSettingsHeader(
            icon = Icons.Outlined.Settings,
            title = "Application Settings",
            subtitle = connectionSummary
        )

        Spacer(Modifier.height(8.dp))

        SharedNavigationRow(
            title = "Connection Settings",
            value = connectionSummary,
            icon = Icons.Rounded.Public,
            onClick = onConnectionClick
        )

        SharedNavigationRow(
            title = "Subscriptions & Sharing",
            value = subscriptionsCount.subscriptionSummary(),
            icon = Icons.Outlined.Share,
            onClick = onSubscriptionsClick
        )

        SharedNavigationRow(
            title = "Update Settings",
            value = "olcbox + Amnezia · every ${updateSettings.intervalHours}h",
            icon = Icons.Outlined.Refresh,
            onClick = onUpdatesClick
        )

        SharedNavigationRow(
            title = "Application Logs",
            value = "Diagnostics and export",
            icon = Icons.Outlined.History,
            onClick = onLogsClick
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${CurrentAppInfo.value.name} ${CurrentAppInfo.value.version}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = CurrentAppInfo.value.sourceAttribution,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SharedConnectionSettingsContent(
    summary: String,
    details: List<Pair<String, String>>,
    socksProxySettings: ApplicationSocksProxySettings?,
    routingModeTitle: String,
    onConnectionModeClick: () -> Unit,
    onSocksProxyClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Connection Settings",
            subtitle = summary,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SharedNavigationRow(
                title = "Connection Mode",
                value = routingModeTitle,
                icon = Icons.Rounded.Public,
                onClick = onConnectionModeClick
            )

            if (socksProxySettings != null) {
                SharedNavigationRow(
                    title = "SOCKS5 Proxy",
                    value = "${socksProxySettings.host}:${socksProxySettings.port}",
                    icon = Icons.Rounded.Public,
                    onClick = onSocksProxyClick
                )
            }

            details
                .filterNot { (title, _) -> title.equals("Mode", ignoreCase = true) }
                .forEach { (title, value) ->
                    SharedInfoRow(title = title, value = value)
                }
        }
    }
}

@Composable
private fun SharedConnectionModeSettingsContent(
    options: List<ApplicationRoutingModeOption>,
    selectedId: String,
    onSelected: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Connection Mode",
            subtitle = options.firstOrNull { it.id == selectedId }?.title
                ?: "Local SOCKS5 proxy",
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { option ->
                SharedSelectableSettingsCard(
                    selected = option.id == selectedId,
                    icon = Icons.Rounded.Public,
                    title = option.title,
                    subtitle = option.subtitle,
                    onClick = { onSelected(option.id) }
                )
            }
        }
    }
}

@Composable
private fun SharedSocksProxySettingsContent(
    settings: ApplicationSocksProxySettings,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedPort by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var editedUsername by remember(settings.username) { mutableStateOf(settings.username) }
    var editedPassword by remember(settings.password) { mutableStateOf(settings.password) }
    val parsedPort = editedPort.toIntOrNull()
    val portValid = parsedPort != null && ApplicationSocksProxySettings.isValidPort(parsedPort)
    val settingsChanged = parsedPort != settings.port ||
            editedUsername != settings.username ||
            editedPassword != settings.password
    val canSave = portValid && settingsChanged

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "SOCKS5 Proxy",
            subtitle = settings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SharedSectionLabel("Endpoint")
            SharedSocksProxyTextField(
                value = editedPort,
                onValueChange = { value ->
                    editedPort = value.filter { it.isDigit() }.take(5)
                },
                label = "Port",
                placeholder = ApplicationSocksProxySettings.DEFAULT_PORT.toString(),
                isError = editedPort.isBlank() || !portValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    editedPort.isBlank() -> "Port is required"
                    !portValid -> "Use ${ApplicationSocksProxySettings.MIN_PORT}-${ApplicationSocksProxySettings.MAX_PORT}"
                    parsedPort != settings.port && isConnectionActive -> "Saving restarts the active connection"
                    parsedPort != settings.port -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )

            SharedSectionLabel("Credentials")
            SharedSocksProxyTextField(
                value = editedUsername,
                onValueChange = { editedUsername = it.take(ApplicationSocksProxySettings.MAX_CREDENTIAL_LENGTH) },
                label = "Username",
                placeholder = "Optional username",
                isError = false,
                leadingIcon = Icons.Rounded.Person,
                supportingText = when {
                    editedUsername != settings.username && isConnectionActive -> "Saving restarts the active connection"
                    editedUsername != settings.username -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            SharedSocksProxyTextField(
                value = editedPassword,
                onValueChange = { editedPassword = it.take(ApplicationSocksProxySettings.MAX_CREDENTIAL_LENGTH) },
                label = "Password",
                placeholder = "Optional password",
                isError = false,
                leadingIcon = Icons.Rounded.Key,
                supportingText = when {
                    editedPassword != settings.password && isConnectionActive -> "Saving restarts the active connection"
                    editedPassword != settings.password -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onProxyPasswordRegenerated) {
                    Text("Regenerate password")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        onProxySettingsSaved(
                            editedUsername,
                            editedPassword,
                            parsedPort ?: settings.port
                        )
                    }
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SharedSocksProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isError: Boolean,
    leadingIcon: ImageVector,
    supportingText: String?,
    keyboardOptions: KeyboardOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedUpdatesSettingsContent(
    settings: AppUpdateSettings,
    statusText: String?,
    offer: UpstreamReleaseInfo?,
    onBack: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onOpenUpstreamReleaseClick: (UpstreamReleaseInfo) -> Unit,
    onDismissUpstreamReleaseClick: (UpstreamReleaseInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SharedDetailHeader(
            title = "Upstream Updates",
            subtitle = "Monitoring olcbox and Amnezia VPN",
            onBack = onBack
        )

        if (offer != null) {
            Spacer(Modifier.height(16.dp))
            SharedUpdateOfferCard(
                offer = offer,
                onOpenGitHub = { onOpenUpstreamReleaseClick(offer) },
                onDismiss = { onDismissUpstreamReleaseClick(offer) }
            )
        }

        Spacer(Modifier.height(18.dp))

        SharedSectionLabel("Check Interval")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateSettings.INTERVAL_PRESETS.forEach { hours ->
                FilterChip(
                    selected = settings.intervalHours == hours,
                    onClick = { onIntervalSelected(hours) },
                    label = { Text("${hours}h") }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Last check",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.lastCheckAtEpochMs?.let { "Checked ${it.formatEpochMs()}" } ?: "Not checked yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onCheckUpdatesClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Check now")
        }
    }
}

@Composable
private fun SharedSubscriptionsSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onCreateFriendPackageClick: (() -> Unit)?,
    onSubscriptionClick: (SubscriptionShareItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SharedDetailHeader(
            title = "Subscriptions",
            subtitle = subscriptions.size.subscriptionSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (subscriptions.isEmpty()) {
                SharedEmptyState(
                    title = "No subscriptions",
                    subtitle = "Import a subscription from the home screen to manage it here."
                )
            } else {
                subscriptions.forEach { item ->
                    SharedSubscriptionRow(
                        item = item,
                        onClick = { onSubscriptionClick(item) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            SharedSectionLabel("Backup & export")
            onCreateFriendPackageClick?.let { onClick ->
                SharedNavigationRow(
                    title = "Package for a friend",
                    value = "olcRTC + your VLESS link + new AmneziaWG peer",
                    icon = Icons.Outlined.Share,
                    showChevron = false,
                    onClick = onClick
                )
            }
            SharedNavigationRow(
                title = "Export full configuration",
                value = "Copy all locations to clipboard",
                icon = Icons.Outlined.ContentPaste,
                showChevron = false,
                onClick = onCopyConfigClick
            )
        }
    }
}

@Composable
private fun SharedSubscriptionDetailsContent(
    item: SubscriptionShareItem,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onShareClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onRefreshIntervalChanged: (Long?) -> Unit,
    onDeleteClick: () -> Unit
) {
    var showRefreshDialog by remember(item.url) { mutableStateOf(false) }
    var refreshIntervalInput by remember(item.url) {
        mutableStateOf(
            item.manualUpdateIntervalMs
                ?.let(::formatSubscriptionRefreshInterval)
                .orEmpty()
        )
    }
    var showDeleteDialog by remember(item.url) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SharedDetailHeader(
            title = "Subscription",
            subtitle = item.name,
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SharedSectionLabel("Overview")
            SharedSubscriptionStatusCard(item)

            SharedSectionLabel("Source")
            SharedSubscriptionSourceCard(
                url = item.url,
                onShareClick = onShareClick
            )

            SharedSectionLabel("Updates")
            SharedSubscriptionUpdateCard(
                item = item,
                isRefreshing = isRefreshing,
                onScheduleClick = {
                    refreshIntervalInput = item.manualUpdateIntervalMs
                        ?.let(::formatSubscriptionRefreshInterval)
                        .orEmpty()
                    showRefreshDialog = true
                },
                onRefreshClick = onRefreshClick
            )

            Spacer(Modifier.height(6.dp))
            SharedDangerAction(
                locationCount = item.locationCount,
                onClick = { showDeleteDialog = true }
            )
            Spacer(Modifier.height(18.dp))
        }
    }

    if (showRefreshDialog) {
        val parsedInterval = refreshIntervalInput
            .takeIf { it.isNotBlank() }
            ?.let(::parseSubscriptionRefreshIntervalMs)
        val hasError = refreshIntervalInput.isNotBlank() && parsedInterval == null

        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text("Refresh schedule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose how often this subscription should be checked.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Auto" to "",
                            "1h" to "1h",
                            "6h" to "6h",
                            "1d" to "1d"
                        ).forEach { (label, value) ->
                            FilterChip(
                                selected = refreshIntervalInput == value,
                                onClick = { refreshIntervalInput = value },
                                label = { Text(label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = refreshIntervalInput,
                        onValueChange = { value ->
                            refreshIntervalInput = value
                                .lowercase()
                                .filter { it.isDigit() || it in "smhd" }
                                .take(8)
                        },
                        label = { Text("Custom interval") },
                        placeholder = { Text("Auto") },
                        supportingText = {
                            Text(
                                if (hasError) {
                                    "Use 5m–30d, for example 10m, 6h, or 1d"
                                } else if (refreshIntervalInput.isBlank()) {
                                    item.sourceScheduleDescription()
                                } else {
                                    "A custom interval overrides the subscription value."
                                }
                            )
                        },
                        isError = hasError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !hasError,
                    onClick = {
                        onRefreshIntervalChanged(parsedInterval)
                        showRefreshDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete subscription?") },
            text = {
                Text(
                    "This will delete “${item.name}” and " +
                        "${item.locationCount.locationCountLabel()} imported from it. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteClick()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SharedLogsSettingsContent(
    logs: List<String>,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SharedDetailHeader(
                title = "Application Logs",
                subtitle = if (logs.isEmpty()) "No entries" else "${logs.size} entries",
                onBack = onBack,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onSaveClick
            ) {
                Text("Save")
            }
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onShareClick
            ) {
                Text("Share")
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp)
            )
        }
    }
}

@Composable
private fun SharedUpdateOfferCard(
    offer: UpstreamReleaseInfo,
    onOpenGitHub: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${offer.project.displayName} updated on GitHub",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Release ${offer.version}. Unified VPN will be updated soon.",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Got it")
                }
                Button(onClick = onOpenGitHub) {
                    Text("Open GitHub")
                }
            }
        }
    }
}

@Composable
private fun SharedSubscriptionRow(
    item: SubscriptionShareItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.locationCount.locationCountLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = item.listScheduleDescription(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SharedSubscriptionStatusCard(item: SubscriptionShareItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SharedStatusMetric(
                label = "Locations",
                value = item.locationCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SharedStatusDivider()
            SharedStatusMetric(
                label = "Updated",
                value = item.lastRefreshAtEpochMs?.relativeTime() ?: "Not yet",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            )
            SharedStatusDivider()
            SharedStatusMetric(
                label = "Next",
                value = item.nextRefreshAtEpochMs?.relativeTime() ?: "On app start",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            )
        }
    }
}

@Composable
private fun SharedStatusMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedStatusDivider() {
    Surface(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    ) {}
}

@Composable
private fun SharedSubscriptionUpdateCard(
    item: SubscriptionShareItem,
    isRefreshing: Boolean,
    onScheduleClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clickable(onClick = onScheduleClick)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.scheduleDescription(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = item.scheduleTitle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            ) {}

            TextButton(
                onClick = onRefreshClick,
                enabled = !isRefreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isRefreshing) "Refreshing…" else "Refresh now")
            }
        }
    }
}

@Composable
private fun SharedSubscriptionSourceCard(
    url: String,
    onShareClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = url.subscriptionHost(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Subscription link",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            IconButton(onClick = onShareClick) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share subscription"
                )
            }
        }
    }
}

@Composable
private fun SharedDangerAction(
    locationCount: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.error
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Delete subscription",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Remove it and ${locationCount.locationCountLabel()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SharedNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SharedInfoRow(
    title: String,
    value: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SharedSelectableSettingsCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SharedSettingsHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SharedDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back"
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SharedSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SharedEmptyState(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

private enum class SharedSettingsRoute {
    Hub,
    Connection,
    ConnectionMode,
    Subscriptions,
    SubscriptionDetails,
    Updates,
    Logs,
    SocksProxy
}

private fun Int.subscriptionSummary(): String {
    return when (this) {
        0 -> "No subscriptions"
        1 -> "1 subscription"
        else -> "$this subscriptions"
    }
}

private fun Int.locationCountLabel(): String {
    return when (this) {
        1 -> "1 location"
        else -> "$this locations"
    }
}

private fun SubscriptionShareItem.scheduleTitle(): String {
    return if (manualUpdateIntervalMs == null) "Automatic" else "Custom schedule"
}

private fun SubscriptionShareItem.scheduleDescription(): String {
    val interval = updateIntervalMs
        ?: updateIntervalHours?.times(60L * 60L * 1_000L)
    return interval?.friendlySchedule() ?: "Uses the subscription schedule"
}

private fun SubscriptionShareItem.sourceScheduleDescription(): String {
    val interval = sourceUpdateIntervalMs
        ?: updateIntervalHours?.times(60L * 60L * 1_000L)
    return interval?.let { "Auto uses ${it.friendlySchedule().lowercase()}." }
        ?: "Auto uses the schedule supplied by the subscription."
}

private fun SubscriptionShareItem.listScheduleDescription(): String {
    val schedule = if (manualUpdateIntervalMs == null) {
        "Automatic"
    } else {
        updateIntervalMs?.friendlySchedule() ?: "Custom schedule"
    }
    val refreshed = lastRefreshAtEpochMs?.relativeTime()?.let { "Updated $it" } ?: "Not updated yet"
    return "$schedule · $refreshed"
}

private fun String.subscriptionHost(): String {
    return substringAfter("://", this)
        .substringBefore('/')
        .substringBefore('?')
        .ifBlank { "Subscription source" }
}

private fun Long.friendlySchedule(): String {
    val minuteMs = 60L * 1_000L
    val hourMs = 60L * minuteMs
    val dayMs = 24L * hourMs
    return when {
        this == dayMs -> "Every day"
        this % dayMs == 0L -> "Every ${this / dayMs} days"
        this == hourMs -> "Every hour"
        this % hourMs == 0L -> "Every ${this / hourMs} hours"
        this == minuteMs -> "Every minute"
        else -> "Every ${(this / minuteMs).coerceAtLeast(1L)} minutes"
    }
}

private fun Long.relativeTime(): String {
    val deltaMs = this - Clock.System.now().toEpochMilliseconds()
    val isFuture = deltaMs > 0L
    val absoluteMs = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else {
        if (deltaMs < 0L) -deltaMs else deltaMs
    }
    val minuteMs = 60L * 1_000L
    val hourMs = 60L * minuteMs
    val dayMs = 24L * hourMs
    val value = when {
        absoluteMs < minuteMs -> "just now"
        absoluteMs < hourMs -> "${absoluteMs / minuteMs} min"
        absoluteMs < dayMs -> "${absoluteMs / hourMs} hr"
        absoluteMs < 7L * dayMs -> "${absoluteMs / dayMs} d"
        else -> return formatEpochMs()
    }
    return if (value == "just now") value else if (isFuture) "in $value" else "$value ago"
}

private fun Long.formatEpochMs(): String {
    return runCatching {
        Instant.fromEpochMilliseconds(this).toString()
            .substringBefore('.')
            .replace('T', ' ')
    }.getOrElse {
        toString()
    }
}
