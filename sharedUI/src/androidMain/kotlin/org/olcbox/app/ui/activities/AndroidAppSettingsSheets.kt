package org.olcbox.app.ui.activities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.model.formatSubscriptionRefreshInterval
import org.olcbox.app.data.model.parseSubscriptionRefreshIntervalMs
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.components.SensitiveValueVisibilityButton
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidInstalledApp
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelProfile
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(
    initialRoute: AppSettingsInitialRoute = AppSettingsInitialRoute.Hub,
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    splitTunnelProfile: AndroidSplitTunnelProfile,
    installedApps: List<AndroidInstalledApp>,
    logs: List<String>,
    dynamicThemeEnabled: Boolean,
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateDownloadProgress: Float?,
    subscriptions: List<SubscriptionShareItem>,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onDismiss: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onCreateFriendPackageClick: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRefreshClick: (String, () -> Unit) -> Unit,
    onSubscriptionRefreshIntervalChanged: (String, Long?) -> Unit,
    onSubscriptionDeleteClick: (String) -> Unit,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit,
    onSplitTunnelProfileSelected: (AndroidSplitTunnelProfile) -> Unit,
    onSplitTunnelModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onSplitTunnelAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onSplitTunnelAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var route by remember(initialRoute) { mutableStateOf(initialRoute.toRoute()) }
    var autoBypassPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var russianBypassPresetEnabled by remember { mutableStateOf(false) }
    var refreshingSubscriptionUrl by remember { mutableStateOf<String?>(null) }

    fun closeSheet(afterClose: () -> Unit = {}) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            afterClose()
        }
    }

    BackHandler {
        route = when (route) {
            AppSettingsRoute.Hub -> {
                closeSheet()
                AppSettingsRoute.Hub
            }

            is AppSettingsRoute.AppList -> AppSettingsRoute.SplitTunneling
            AppSettingsRoute.ConnectionMode,
            AppSettingsRoute.SocksProxy,
            AppSettingsRoute.SplitTunneling -> AppSettingsRoute.ConnectionSettings

            is AppSettingsRoute.SubscriptionDetails -> AppSettingsRoute.SubscriptionsSharing
            else -> AppSettingsRoute.Hub
        }
    }

    ModalBottomSheet(
        onDismissRequest = { closeSheet() },
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
            label = "appSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                AppSettingsRoute.Hub -> AppSettingsHubContent(
                    selectedMode = selectedMode,
                    dynamicThemeEnabled = dynamicThemeEnabled,
                    updateSettings = updateSettings,
                    subscriptionsCount = subscriptions.size,
                    enabled = enabled,
                    onDynamicThemeChanged = onDynamicThemeChanged,
                    onConnectionSettingsClick = { route = AppSettingsRoute.ConnectionSettings },
                    onSubscriptionsSharingClick = { route = AppSettingsRoute.SubscriptionsSharing },
                    onUpdatesClick = { route = AppSettingsRoute.Updates },
                    onApplicationLogsClick = { route = AppSettingsRoute.ApplicationLogs }
                )

                AppSettingsRoute.ConnectionSettings -> ConnectionSettingsContent(
                    selectedMode = selectedMode,
                    proxySettings = proxySettings,
                    splitTunnelSettings = splitTunnelSettings,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.Hub },
                    onConnectionModeClick = { route = AppSettingsRoute.ConnectionMode },
                    onProxySettingsClick = { route = AppSettingsRoute.SocksProxy },
                    onSplitTunnelingClick = { route = AppSettingsRoute.SplitTunneling }
                )

                AppSettingsRoute.ConnectionMode -> ConnectionModeSettingsContent(
                    selectedMode = selectedMode,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onModeSelected = onModeSelected
                )

                AppSettingsRoute.SocksProxy -> SocksProxySettingsContent(
                    proxySettings = proxySettings,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onProxySettingsSaved = onProxySettingsSaved,
                    onProxyPasswordRegenerated = onProxyPasswordRegenerated
                )

                AppSettingsRoute.SplitTunneling -> SplitTunnelingSettingsContent(
                    settings = splitTunnelSettings,
                    profile = splitTunnelProfile,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    selectedMode = selectedMode,
                    onBack = { route = AppSettingsRoute.ConnectionSettings },
                    onProfileSelected = onSplitTunnelProfileSelected,
                    onModeSelected = onSplitTunnelModeSelected,
                    onAppListClick = { list -> route = AppSettingsRoute.AppList(list) }
                )

                is AppSettingsRoute.AppList -> SplitTunnelingAppListContent(
                    list = currentRoute.list,
                    settings = splitTunnelSettings,
                    installedApps = installedApps,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.SplitTunneling },
                    onAppToggled = onSplitTunnelAppToggled,
                    onAppsSelected = onSplitTunnelAppsSelected,
                    autoBypassPackages = autoBypassPackages,
                    onAutoBypassPackagesChanged = { autoBypassPackages = it },
                    russianBypassPresetEnabled = russianBypassPresetEnabled,
                    onRussianBypassPresetEnabledChanged = { russianBypassPresetEnabled = it }
                )

                AppSettingsRoute.ApplicationLogs -> ApplicationLogsSettingsContent(
                    logs = logs,
                    onBack = { route = AppSettingsRoute.Hub },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )

                AppSettingsRoute.SubscriptionsSharing -> SubscriptionsSharingSettingsContent(
                    subscriptions = subscriptions,
                    onBack = { route = AppSettingsRoute.Hub },
                    onCopyConfigClick = onCopyConfigClick,
                    onCreateFriendPackageClick = onCreateFriendPackageClick,
                    onSubscriptionClick = { item ->
                        route = AppSettingsRoute.SubscriptionDetails(item.url)
                    }
                )

                is AppSettingsRoute.SubscriptionDetails -> {
                    val item = subscriptions.firstOrNull { it.url == currentRoute.subscriptionUrl }
                    if (item == null) {
                        SubscriptionsSharingSettingsContent(
                            subscriptions = subscriptions,
                            onBack = { route = AppSettingsRoute.Hub },
                            onCopyConfigClick = onCopyConfigClick,
                            onCreateFriendPackageClick = onCreateFriendPackageClick,
                            onSubscriptionClick = { selected ->
                                route = AppSettingsRoute.SubscriptionDetails(selected.url)
                            }
                        )
                    } else {
                        SubscriptionDetailsSettingsContent(
                            item = item,
                            isRefreshing = refreshingSubscriptionUrl == item.url,
                            onBack = { route = AppSettingsRoute.SubscriptionsSharing },
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
                                route = AppSettingsRoute.SubscriptionsSharing
                            }
                        )
                    }
                }

                AppSettingsRoute.Updates -> UpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    downloadProgress = updateDownloadProgress,
                    onBack = { route = AppSettingsRoute.Hub },
                    onIntervalSelected = onUpdateIntervalSelected,
                    onCheckUpdatesClick = onCheckUpdatesClick
                )
            }
        }
    }
}

internal enum class AppSettingsInitialRoute {
    Hub,
    SplitTunneling
}

@Composable
private fun AppSettingsHubContent(
    selectedMode: AndroidConnectionMode,
    dynamicThemeEnabled: Boolean,
    updateSettings: AppUpdateSettings,
    subscriptionsCount: Int,
    enabled: Boolean,
    onDynamicThemeChanged: (Boolean) -> Unit,
    onConnectionSettingsClick: () -> Unit,
    onSubscriptionsSharingClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onApplicationLogsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSheetHeader(
            icon = Icons.Outlined.Settings,
            title = "Application Settings",
            subtitle = selectedMode.shortLabel()
        )

        Spacer(Modifier.height(8.dp))

        SettingsSwitchRow(
            title = "Dynamic Theme",
            value = if (dynamicThemeEnabled) {
                "Using Android system colors"
            } else {
                "Using Unified VPN colors"
            },
            icon = Icons.Outlined.Palette,
            checked = dynamicThemeEnabled,
            enabled = true,
            onCheckedChange = onDynamicThemeChanged
        )

        SettingsNavigationRow(
            title = "Connection Settings",
            value = "Mode, SOCKS5 proxy, and app routing",
            icon = selectedMode.icon(),
            enabled = enabled,
            onClick = onConnectionSettingsClick
        )

        SettingsNavigationRow(
            title = "Subscriptions & Sharing",
            value = subscriptionsCount.subscriptionSummary(),
            icon = Icons.Outlined.Share,
            enabled = true,
            onClick = onSubscriptionsSharingClick
        )

        SettingsNavigationRow(
            title = "Update Settings",
            value = "Unified VPN + upstream · every ${updateSettings.intervalHours}h",
            icon = Icons.Outlined.Refresh,
            enabled = true,
            onClick = onUpdatesClick
        )

        SettingsNavigationRow(
            title = "Application Logs",
            value = "Diagnostics and export",
            icon = Icons.Outlined.History,
            enabled = true,
            onClick = onApplicationLogsClick
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${CurrentAppInfo.value.name} ${CurrentAppInfo.value.version} · " +
                    "olcrtc ${CurrentAppInfo.value.olcrtcSha.take(12)} · " +
                    "awg ${CurrentAppInfo.value.awgCoreSha.take(12)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ConnectionSettingsContent(
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    enabled: Boolean,
    onBack: () -> Unit,
    onConnectionModeClick: () -> Unit,
    onProxySettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "Connection Settings",
            subtitle = selectedMode.settingsSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNavigationRow(
                title = "Connection Mode",
                value = selectedMode.settingsSummary(),
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = onConnectionModeClick
            )
            SettingsNavigationRow(
                title = "SOCKS5 Proxy",
                value = "${proxySettings.host}:${proxySettings.port}",
                icon = Icons.Rounded.Public,
                enabled = enabled,
                onClick = onProxySettingsClick
            )
            SettingsNavigationRow(
                title = "Split Tunneling",
                value = splitTunnelSettings.settingsSummary(),
                icon = Icons.Outlined.Apps,
                enabled = enabled,
                onClick = onSplitTunnelingClick
            )
        }
    }
}

@Composable
private fun ConnectionModeSettingsContent(
    selectedMode: AndroidConnectionMode,
    enabled: Boolean,
    onBack: () -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit
) {
    val options = listOf(AndroidConnectionMode.Tun, AndroidConnectionMode.Proxy)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "Connection Mode",
            subtitle = selectedMode.subtitle(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { mode ->
                ConnectionModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }
    }
}

@Composable
private fun SocksProxySettingsContent(
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedHost by remember(proxySettings.host) { mutableStateOf(proxySettings.host) }
    var editedPort by remember(proxySettings.port) { mutableStateOf(proxySettings.port.toString()) }
    var editedUsername by remember(proxySettings.username) { mutableStateOf(proxySettings.username) }
    var editedPassword by remember(proxySettings.password) { mutableStateOf(proxySettings.password) }
    var passwordVisible by remember(proxySettings.password) { mutableStateOf(false) }
    val parsedPort = editedPort.toIntOrNull()
    val hostValid = editedHost.isNotBlank()
    val portValid = parsedPort != null && AndroidSocksProxySettings.isValidPort(parsedPort)
    val hostChanged = editedHost != proxySettings.host
    val portChanged = parsedPort != null && parsedPort != proxySettings.port
    val usernameChanged = editedUsername != proxySettings.username
    val passwordChanged = editedPassword != proxySettings.password
    val settingsChanged = hostChanged || portChanged || usernameChanged || passwordChanged
    val canSave = hostValid &&
            portValid &&
            editedUsername.isNotBlank() &&
            editedPassword.isNotBlank() &&
            settingsChanged &&
            enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "SOCKS5 Proxy",
            subtitle = proxySettings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        SocksProxySettingsForm(
            host = editedHost,
            port = editedPort,
            username = editedUsername,
            password = editedPassword,
            passwordVisible = passwordVisible,
            hostValid = hostValid,
            portValid = portValid,
            hostChanged = hostChanged,
            portChanged = portChanged,
            usernameChanged = usernameChanged,
            passwordChanged = passwordChanged,
            canSave = canSave,
            enabled = enabled,
            isConnectionActive = isConnectionActive,
            onHostChanged = { value ->
                editedHost = value
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(AndroidSocksProxySettings.MAX_HOST_LENGTH)
            },
            onPortChanged = { value ->
                editedPort = value.filter { it.isDigit() }.take(MAX_PROXY_PORT_LENGTH)
            },
            onUsernameChanged = { value -> editedUsername = value.take(MAX_PROXY_USERNAME_LENGTH) },
            onPasswordChanged = { value -> editedPassword = value.take(MAX_PROXY_PASSWORD_LENGTH) },
            onPasswordVisibilityChanged = { passwordVisible = it },
            onSaveSettings = {
                onProxySettingsSaved(
                    editedHost,
                    editedUsername,
                    editedPassword,
                    parsedPort ?: proxySettings.port
                )
            },
            onRegeneratePassword = onProxyPasswordRegenerated
        )
    }
}

@Composable
private fun SplitTunnelingSettingsContent(
    settings: AndroidSplitTunnelSettings,
    profile: AndroidSplitTunnelProfile,
    enabled: Boolean,
    isConnectionActive: Boolean,
    selectedMode: AndroidConnectionMode,
    onBack: () -> Unit,
    onProfileSelected: (AndroidSplitTunnelProfile) -> Unit,
    onModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onAppListClick: (AndroidSplitTunnelList) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "Split Tunneling",
            subtitle = "${profile.title()}: ${settings.mode.statusTitle(settings)}",
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AndroidSplitTunnelProfile.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AndroidSplitTunnelProfile.entries.size
                    ),
                    selected = profile == item,
                    onClick = { onProfileSelected(item) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = item.title(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        SplitTunnelStatusCard(
            settings = settings,
            selectedMode = selectedMode,
            isConnectionActive = isConnectionActive
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel("Routing Behavior")

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AndroidSplitTunnelMode.entries.forEach { mode ->
                SplitTunnelModeOption(
                    mode = mode,
                    settings = settings,
                    selected = settings.mode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (settings.mode) {
            AndroidSplitTunnelMode.AllApps -> SplitTunnelNoListCard()
            AndroidSplitTunnelMode.ProxySelected -> SplitTunnelAppListAction(
                title = "Apps Using Unified VPN",
                value = settings.proxyPackages.activeListValue(requireSelection = true),
                icon = Icons.Outlined.Shield,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Proxy) }
            )

            AndroidSplitTunnelMode.BypassSelected -> SplitTunnelAppListAction(
                title = "Bypassed Apps",
                value = settings.bypassPackages.activeListValue(requireSelection = false),
                icon = Icons.Outlined.Apps,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Bypass) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitTunnelingAppListContent(
    list: AndroidSplitTunnelList,
    settings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    enabled: Boolean,
    onBack: () -> Unit,
    onAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit,
    autoBypassPackages: Set<String>,
    onAutoBypassPackagesChanged: (Set<String>) -> Unit,
    russianBypassPresetEnabled: Boolean,
    onRussianBypassPresetEnabledChanged: (Boolean) -> Unit
) {
    var query by remember(list) { mutableStateOf("") }

    // Не просто scrollToItem(0): keyed LazyColumn может успеть сохранить старый visible key
    // и слегка увести список к элементу, который переехал. Для bulk/search-сценариев
    // пересоздаём LazyListState, чтобы список гарантированно начинался сверху.
    var listStateResetVersion by remember(list) { mutableStateOf(0) }
    val listScrollState = key(listStateResetVersion) { rememberLazyListState() }
    val focusManager = LocalFocusManager.current

    fun resetListScrollToTop() {
        listStateResetVersion += 1
    }

    val selectedPackages = settings.packagesFor(list)

    val russianBypassPackages = remember(installedApps) {
        installedApps
            .map { it.packageName }
            .filter { it.matchesRussianBypassPackage() }
            .toSet()
    }

    val russianBypassActive = list == AndroidSplitTunnelList.Bypass &&
            russianBypassPresetEnabled &&
            russianBypassPackages.isNotEmpty()

    val activeAutoBypassPackages = autoBypassPackages
        .intersect(russianBypassPackages)
        .intersect(selectedPackages)

    val selectedRussianBypassPackagesCount = selectedPackages
        .count { it in russianBypassPackages }

    // Это snapshot порядка, а не всегда актуальное состояние selection.
    // Ручной toggle не должен мгновенно двигать строку вверх/вниз — иначе UX ощущается как jump.
    var sortAutoBypassPackages by remember(list) {
        mutableStateOf(activeAutoBypassPackages)
    }

    var sortSelectedPackages by remember(list) {
        mutableStateOf(selectedPackages)
    }

    val normalizedQuery = query.trim().lowercase()
    val appListEntries = remember(installedApps) {
        installedApps.map { app ->
            AndroidAppListEntry(
                app = app,
                labelSortKey = app.label.lowercase(),
                packageSortKey = app.packageName.lowercase()
            )
        }
    }
    val filteredApps = remember(appListEntries, normalizedQuery, sortSelectedPackages, sortAutoBypassPackages) {
        val apps = if (normalizedQuery.isBlank()) {
            appListEntries
        } else {
            appListEntries.filter { entry ->
                entry.labelSortKey.contains(normalizedQuery) ||
                        entry.packageSortKey.contains(normalizedQuery)
            }
        }
        apps.sortedWith(
            compareBy<AndroidAppListEntry> {
                when (it.app.packageName) {
                    in sortAutoBypassPackages -> 0
                    in sortSelectedPackages -> 1
                    else -> 2
                }
            }.thenBy { it.labelSortKey }.thenBy { it.packageSortKey }
        ).map { it.app }
    }

    LaunchedEffect(list, russianBypassPackages, autoBypassPackages, russianBypassPresetEnabled) {
        if (list == AndroidSplitTunnelList.Bypass) {
            val cleanedAutoPackages = autoBypassPackages.intersect(russianBypassPackages)

            if (cleanedAutoPackages != autoBypassPackages) {
                onAutoBypassPackagesChanged(cleanedAutoPackages)
                sortAutoBypassPackages = sortAutoBypassPackages.intersect(cleanedAutoPackages)
            }

            if (russianBypassPackages.isEmpty() && russianBypassPresetEnabled) {
                onRussianBypassPresetEnabledChanged(false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = list.title(),
            subtitle = if (list == AndroidSplitTunnelList.Bypass && russianBypassActive) {
                RUSSIAN_BYPASS_ACCURACY_MESSAGE
            } else {
                list.selectionSubtitle(selectedPackages.size)
            },
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                if (value != query) {
                    resetListScrollToTop()
                    query = value
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null
                )
            },
            label = { Text("Search apps") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        if (list == AndroidSplitTunnelList.Bypass) {
            Spacer(Modifier.height(10.dp))

            RussianBypassPresetChips(
                active = russianBypassActive,
                enabled = enabled && russianBypassPackages.isNotEmpty(),
                value = russianBypassPackages.russianBypassPresetValue(
                    autoCount = activeAutoBypassPackages.size,
                    selectedMatchedCount = selectedRussianBypassPackagesCount,
                    presetActive = russianBypassActive
                ),
                onClick = {
                    focusManager.clearFocus()

                    val activatingRussianBypass = !russianBypassActive

                    val nextAutoPackages = if (activatingRussianBypass) {
                        russianBypassPackages - selectedPackages
                    } else {
                        emptySet()
                    }

                    val nextPackages = if (activatingRussianBypass) {
                        selectedPackages + nextAutoPackages
                    } else {
                        selectedPackages - activeAutoBypassPackages
                    }

                    onRussianBypassPresetEnabledChanged(activatingRussianBypass)
                    onAutoBypassPackagesChanged(nextAutoPackages)

                    sortAutoBypassPackages = nextAutoPackages
                    sortSelectedPackages = nextPackages

                    query = ""
                    resetListScrollToTop()

                    onAppsSelected(list, nextPackages)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (filteredApps.isEmpty()) {
            EmptyAppsState(
                title = if (installedApps.isEmpty()) "No apps found" else "No matching apps",
                subtitle = if (installedApps.isEmpty()) {
                    "Install launchable apps to configure routing rules."
                } else {
                    "Try another app name or package."
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listScrollState,
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { app -> app.packageName }
                ) { app ->
                    val packageName = app.packageName
                    val selected = packageName in selectedPackages
                    val autoAdded = list == AndroidSplitTunnelList.Bypass &&
                            packageName in activeAutoBypassPackages

                    val russianPresetMatched = list == AndroidSplitTunnelList.Bypass &&
                            russianBypassActive &&
                            selected &&
                            packageName in russianBypassPackages

                    SplitTunnelAppRow(
                        app = app,
                        selected = selected,
                        autoSelected = russianPresetMatched,
                        enabled = enabled,
                        onClick = {
                            focusManager.clearFocus()

                            val removing = packageName in selectedPackages
                            val nextSelectedPackages = if (removing) {
                                selectedPackages - packageName
                            } else {
                                selectedPackages + packageName
                            }
                            val nextSelectedRussianPackages = nextSelectedPackages.intersect(russianBypassPackages)

                            val nextAutoPackages = when {
                                list == AndroidSplitTunnelList.Bypass &&
                                        russianBypassActive &&
                                        nextSelectedRussianPackages.isEmpty() -> {
                                    emptySet()
                                }

                                autoAdded -> autoBypassPackages - packageName

                                !removing &&
                                        russianBypassActive &&
                                        packageName in russianBypassPackages -> {
                                    autoBypassPackages + packageName
                                }

                                else -> autoBypassPackages
                            }

                            if (list == AndroidSplitTunnelList.Bypass &&
                                russianBypassActive &&
                                nextSelectedRussianPackages.isEmpty()
                            ) {
                                onRussianBypassPresetEnabledChanged(false)
                            }

                            if (nextAutoPackages != autoBypassPackages) {
                                onAutoBypassPackagesChanged(nextAutoPackages)
                            }

                            // Важно: не меняем sortSelectedPackages/sortAutoBypassPackages на одиночный toggle.
                            // Иначе строка сразу переезжает между группами, а LazyColumn сохраняет её key
                            // как first visible item — отсюда микроскролл к package, который пользователь убрал.
                            onAppToggled(list, packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationLogsSettingsContent(
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
            SettingsDetailHeader(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesSettingsContent(
    settings: AppUpdateSettings,
    statusText: String?,
    downloadProgress: Float?,
    onBack: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = "Updates",
            subtitle = "Unified VPN ${CurrentAppInfo.value.version}",
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel("Check Interval")
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
                    text = settings.lastCheckAtEpochMs?.formatDateTime() ?: "Not checked yet",
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
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
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
private fun SubscriptionsSharingSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onCreateFriendPackageClick: () -> Unit,
    onSubscriptionClick: (SubscriptionShareItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
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
                EmptyAppsState(
                    title = "No subscriptions",
                    subtitle = "Import a subscription from the home screen to manage it here."
                )
            } else {
                subscriptions.forEach { item ->
                    SubscriptionShareRow(
                        item = item,
                        onClick = { onSubscriptionClick(item) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            SettingsSectionLabel("Backup & export")
            SettingsNavigationRow(
                title = "Package for a friend",
                value = "olcRTC + your VLESS link + new AmneziaWG peer",
                icon = Icons.Outlined.Share,
                enabled = true,
                showChevron = false,
                onClick = onCreateFriendPackageClick
            )
            SettingsNavigationRow(
                title = "Export full configuration",
                value = "Copy all locations to clipboard",
                icon = Icons.Outlined.ContentPaste,
                enabled = true,
                showChevron = false,
                onClick = onCopyConfigClick
            )
        }
    }
}

@Composable
private fun SubscriptionDetailsSettingsContent(
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
        SettingsDetailHeader(
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
            SettingsSectionLabel("Overview")
            SubscriptionStatusCard(item)

            SettingsSectionLabel("Source")
            SubscriptionSourceCard(
                url = item.url,
                onShareClick = onShareClick
            )

            SettingsSectionLabel("Updates")
            SubscriptionUpdateCard(
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
            SubscriptionDangerAction(
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
private fun SubscriptionShareRow(
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
private fun SubscriptionStatusCard(item: SubscriptionShareItem) {
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
            SubscriptionStatusMetric(
                label = "Locations",
                value = item.locationCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SubscriptionStatusDivider()
            SubscriptionStatusMetric(
                label = "Updated",
                value = item.lastRefreshAtEpochMs?.relativeSubscriptionTime() ?: "Not yet",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            )
            SubscriptionStatusDivider()
            SubscriptionStatusMetric(
                label = "Next",
                value = item.nextRefreshAtEpochMs?.relativeSubscriptionTime()
                    ?: "On app start",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            )
        }
    }
}

@Composable
private fun SubscriptionStatusMetric(
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
private fun SubscriptionStatusDivider() {
    Surface(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    ) {}
}

@Composable
private fun SubscriptionUpdateCard(
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
private fun SubscriptionSourceCard(
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
private fun SubscriptionDangerAction(
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
private fun SettingsNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    value: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsSheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderIcon(icon = icon)

        Spacer(Modifier.width(14.dp))

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(11.dp)
        )
    }
}

@Composable
private fun ConnectionModeOption(
    mode: AndroidConnectionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingsCard(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.label(),
        subtitle = mode.description(),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelModeOption(
    mode: AndroidSplitTunnelMode,
    settings: AndroidSplitTunnelSettings,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SplitTunnelRoutingOption(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.title(),
        subtitle = mode.subtitle(settings),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelRoutingOption(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "splitTunnelRoutingOptionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "splitTunnelRoutingOptionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
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
                    modifier = Modifier.padding(9.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun SelectableSettingsCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "selectableSettingsCardContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "selectableSettingsCardBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
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
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun SplitTunnelStatusCard(
    settings: AndroidSplitTunnelSettings,
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = settings.mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = settings.mode.statusTitle(settings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = splitTunnelStatusSubtitle(selectedMode, isConnectionActive),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelNoListCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "No app list needed",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Every app follows the same TUN route",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelAppListAction(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SettingsSectionLabel("App List")

    Spacer(Modifier.height(8.dp))

    SettingsNavigationRow(
        title = title,
        value = value,
        icon = icon,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun SocksProxySettingsForm(
    host: String,
    port: String,
    username: String,
    password: String,
    passwordVisible: Boolean,
    hostValid: Boolean,
    portValid: Boolean,
    hostChanged: Boolean,
    portChanged: Boolean,
    usernameChanged: Boolean,
    passwordChanged: Boolean,
    canSave: Boolean,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordVisibilityChanged: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onRegeneratePassword: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel("Endpoint")

            SocksProxyTextField(
                value = host,
                onValueChange = onHostChanged,
                label = "Listen address",
                placeholder = AndroidSocksProxySettings.DEFAULT_HOST,
                enabled = enabled,
                isError = !hostValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    !hostValid -> "Listen address is required"
                    hostChanged && isConnectionActive -> "Saving restarts the active connection"
                    hostChanged -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = port,
                onValueChange = onPortChanged,
                label = "Port",
                placeholder = AndroidSocksProxySettings.DEFAULT_PORT.toString(),
                enabled = enabled,
                isError = port.isBlank() || !portValid,
                leadingIcon = Icons.Rounded.Public,
                supportingText = when {
                    port.isBlank() -> "Port is required"
                    !portValid -> "Use ${AndroidSocksProxySettings.MIN_PORT}-${AndroidSocksProxySettings.MAX_PORT}"
                    portChanged && isConnectionActive -> "Saving restarts the active connection"
                    portChanged -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel("Credentials")

            SocksProxyTextField(
                value = username,
                onValueChange = onUsernameChanged,
                label = "Username",
                placeholder = "olcbox...",
                enabled = enabled,
                isError = username.isBlank(),
                leadingIcon = Icons.Rounded.Person,
                supportingText = when {
                    username.isBlank() -> "Username is required"
                    usernameChanged && isConnectionActive -> "Saving restarts the active connection"
                    usernameChanged -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = "Password",
                placeholder = "Generated password",
                enabled = enabled,
                isError = password.isBlank(),
                leadingIcon = Icons.Rounded.Key,
                supportingText = when {
                    password.isBlank() -> "Password is required"
                    passwordChanged && isConnectionActive -> "Saving restarts the active connection"
                    passwordChanged -> "Unsaved change"
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                sensitive = true,
                sensitiveVisible = passwordVisible,
                onSensitiveVisibilityChanged = onPasswordVisibilityChanged
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = enabled,
                onClick = onRegeneratePassword
            ) {
                Text("Regenerate password")
            }

            Spacer(Modifier.width(8.dp))

            Button(
                enabled = canSave,
                onClick = onSaveSettings
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
        }
    }
}

@Composable
private fun SocksProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    leadingIcon: ImageVector,
    supportingText: String?,
    keyboardOptions: KeyboardOptions,
    sensitive: Boolean = false,
    sensitiveVisible: Boolean = false,
    onSensitiveVisibilityChanged: (Boolean) -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = if (sensitive && !sensitiveVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (sensitive) {
            {
                SensitiveValueVisibilityButton(
                    visible = sensitiveVisible,
                    onVisibilityChanged = onSensitiveVisibilityChanged,
                    valueLabel = label,
                    enabled = enabled && value.isNotEmpty()
                )
            }
        } else {
            null
        }
    )
}

@Composable
private fun RussianBypassPresetChips(
    active: Boolean,
    enabled: Boolean,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = active,
            enabled = enabled,
            onClick = onClick,
            label = {
                Text(if (active) "RU bypass on" else "Bypass RU apps")
            },
            leadingIcon = {
                Icon(
                    imageVector = if (active) Icons.Rounded.Check else Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        RussianBypassInfoPill(value = value)
    }
}

@Composable
private fun RussianBypassInfoPill(value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SplitTunnelAppRow(
    app: AndroidInstalledApp,
    selected: Boolean,
    autoSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val iconBitmap = rememberAppIcon(app.packageName)
    val colors = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHigh
            selected -> colors.secondaryContainer
            else -> colors.surfaceContainer
        },
        label = "splitTunnelAppRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary.copy(alpha = 0.72f)
            selected -> colors.primary
            else -> colors.outlineVariant
        },
        label = "splitTunnelAppRowBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHighest
            selected -> colors.primary
            else -> colors.surfaceVariant
        },
        label = "splitTunnelAppRowIconContainer"
    )
    val iconContentColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary
            selected -> colors.onPrimary
            else -> colors.onSurfaceVariant
        },
        label = "splitTunnelAppRowIconContent"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = app.label.initials(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.packageName,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (autoSelected) {
                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.surfaceContainerHighest,
                    contentColor = colors.tertiary
                ) {
                    Text(
                        text = "RU",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Checkbox(
                checked = selected,
                enabled = enabled,
                onCheckedChange = { onClick() }
            )
        }
    }
}

@Composable
private fun EmptyAppsState(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val iconState = produceState<ImageBitmap?>(initialValue = null, packageName, context) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toImageBitmap(sizePx = 96)
            }.getOrNull()
        }
    }
    return iconState.value
}

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val width = intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val height = intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

private sealed class AppSettingsRoute(val depth: Int) {
    object Hub : AppSettingsRoute(0)
    object ConnectionSettings : AppSettingsRoute(1)
    object ConnectionMode : AppSettingsRoute(1)
    object SocksProxy : AppSettingsRoute(1)
    object SplitTunneling : AppSettingsRoute(1)
    object SubscriptionsSharing : AppSettingsRoute(1)
    data class SubscriptionDetails(val subscriptionUrl: String) : AppSettingsRoute(2)
    object Updates : AppSettingsRoute(1)
    object ApplicationLogs : AppSettingsRoute(1)
    data class AppList(val list: AndroidSplitTunnelList) : AppSettingsRoute(2)
}

private fun AppSettingsInitialRoute.toRoute(): AppSettingsRoute {
    return when (this) {
        AppSettingsInitialRoute.Hub -> AppSettingsRoute.Hub
        AppSettingsInitialRoute.SplitTunneling -> AppSettingsRoute.SplitTunneling
    }
}

private fun AndroidConnectionMode.label(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "Proxy"
    }
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
    return interval?.friendlySubscriptionSchedule() ?: "Uses the subscription schedule"
}

private fun SubscriptionShareItem.sourceScheduleDescription(): String {
    val interval = sourceUpdateIntervalMs
        ?: updateIntervalHours?.times(60L * 60L * 1_000L)
    return interval?.let { "Auto uses ${it.friendlySubscriptionSchedule().lowercase()}." }
        ?: "Auto uses default schedule."
}

private fun SubscriptionShareItem.listScheduleDescription(): String {
    val schedule = if (manualUpdateIntervalMs == null) {
        "Automatic"
    } else {
        updateIntervalMs?.friendlySubscriptionSchedule() ?: "Custom schedule"
    }
    val refreshed = lastRefreshAtEpochMs
        ?.relativeSubscriptionTime()
        ?.let { "Updated $it" }
        ?: "Not updated yet"
    return "$schedule · $refreshed"
}

private fun String.subscriptionHost(): String {
    return substringAfter("://", this)
        .substringBefore('/')
        .substringBefore('?')
        .ifBlank { "Subscription source" }
}

private fun Long.friendlySubscriptionSchedule(): String {
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

private fun Long.relativeSubscriptionTime(): String {
    val deltaMs = this - System.currentTimeMillis()
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
        else -> return formatDateTime()
    }
    return if (value == "just now") value else if (isFuture) "in $value" else "$value ago"
}

private fun Long.formatDateTime(): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
}

private fun AndroidConnectionMode.shortLabel(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "SOCKS"
    }
}

private fun AndroidConnectionMode.subtitle(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "Full tunnel"
        AndroidConnectionMode.Proxy -> "Local SOCKS5 proxy"
    }
}

private fun AndroidConnectionMode.settingsSummary(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN · Full tunnel"
        AndroidConnectionMode.Proxy -> "Proxy · Local SOCKS5"
    }
}

private fun AndroidConnectionMode.description(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "System VPN interface"
        AndroidConnectionMode.Proxy -> "Local SOCKS endpoint"
    }
}

private fun AndroidConnectionMode.icon() = when (this) {
    AndroidConnectionMode.Tun -> Icons.Outlined.Shield
    AndroidConnectionMode.Proxy -> Icons.Rounded.Public
}

private fun AndroidSplitTunnelSettings.settingsSummary(): String {
    return when (mode) {
        AndroidSplitTunnelMode.AllApps -> "All apps"
        AndroidSplitTunnelMode.ProxySelected -> if (proxyPackages.isEmpty()) {
            "Selected apps only"
        } else {
            "Only ${appCount(proxyPackages.size)}"
        }

        AndroidSplitTunnelMode.BypassSelected -> if (bypassPackages.isEmpty()) {
            "Bypass selected apps"
        } else {
            "${appCount(bypassPackages.size)} bypassed"
        }
    }
}

private fun AndroidSplitTunnelProfile.title(): String {
    return when (this) {
        AndroidSplitTunnelProfile.OlcRtc -> "olcRTC"
        AndroidSplitTunnelProfile.External -> "VLESS / AWG"
    }
}

private fun AndroidSplitTunnelSettings.packagesFor(list: AndroidSplitTunnelList): Set<String> {
    return when (list) {
        AndroidSplitTunnelList.Proxy -> proxyPackages
        AndroidSplitTunnelList.Bypass -> bypassPackages
    }
}

private fun AndroidSplitTunnelMode.title(): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> "All Apps"
        AndroidSplitTunnelMode.ProxySelected -> "Selected Apps Only"
        AndroidSplitTunnelMode.BypassSelected -> "Bypass Selected"
    }
}

private fun AndroidSplitTunnelMode.subtitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> "Every app uses Unified VPN"
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            "Choose apps that use Unified VPN"
        } else {
            "${appCount(settings.proxyPackages.size)} use Unified VPN"
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            "Choose apps that bypass Unified VPN"
        } else {
            "${appCount(settings.bypassPackages.size)} bypass Unified VPN"
        }
    }
}

private fun AndroidSplitTunnelMode.statusTitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> "All apps use Unified VPN"
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            "No apps selected"
        } else {
            "Only ${appCount(settings.proxyPackages.size)} use Unified VPN"
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            "No apps bypass Unified VPN"
        } else {
            "${appCount(settings.bypassPackages.size)} bypass Unified VPN"
        }
    }
}

private fun AndroidSplitTunnelMode.icon() = when (this) {
    AndroidSplitTunnelMode.AllApps -> Icons.Outlined.Shield
    AndroidSplitTunnelMode.ProxySelected -> Icons.Outlined.Shield
    AndroidSplitTunnelMode.BypassSelected -> Icons.Outlined.Apps
}

private fun AndroidSplitTunnelList.title(): String {
    return when (this) {
        AndroidSplitTunnelList.Proxy -> "Apps Using Unified VPN"
        AndroidSplitTunnelList.Bypass -> "Bypassed Apps"
    }
}

private fun AndroidSplitTunnelList.selectionSubtitle(count: Int): String {
    return when (this) {
        AndroidSplitTunnelList.Proxy -> "${appCount(count)} use Unified VPN"
        AndroidSplitTunnelList.Bypass -> "${appCount(count)} bypassed"
    }
}

private fun Set<String>.russianBypassPresetValue(
    autoCount: Int,
    selectedMatchedCount: Int,
    presetActive: Boolean
): String {
    return when {
        isEmpty() -> "No matching installed apps"
        !presetActive -> "${appCount(size)} matched by package"
        selectedMatchedCount == 0 -> "No RU apps selected"
        autoCount == 0 -> "${appCount(selectedMatchedCount)} already selected"
        autoCount == selectedMatchedCount -> "${appCount(autoCount)} auto-bypassed"
        else -> "$autoCount auto · ${selectedMatchedCount - autoCount} manual"
    }
}

private fun String.matchesRussianBypassPackage(): Boolean {
    val packageName = lowercase()
    return packageName in RUSSIAN_BYPASS_PACKAGE_NAMES ||
            RUSSIAN_BYPASS_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
}

private fun Set<String>.activeListValue(requireSelection: Boolean): String {
    return when {
        isNotEmpty() -> appCount(size)
        requireSelection -> "Required"
        else -> "No bypassed apps"
    }
}

private fun splitTunnelStatusSubtitle(
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
): String {
    return when {
        selectedMode == AndroidConnectionMode.Proxy -> "Saved for TUN mode"
        isConnectionActive -> "Applies when settings closes"
        else -> "TUN mode routing rule"
    }
}

private fun String.initials(): String {
    val words = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private fun appCount(count: Int): String {
    return if (count == 1) "1 app" else "$count apps"
}

private data class AndroidAppListEntry(
    val app: AndroidInstalledApp,
    val labelSortKey: String,
    val packageSortKey: String
)

private const val MAX_PROXY_USERNAME_LENGTH = 64
private const val MAX_PROXY_PASSWORD_LENGTH = 64
private const val MAX_PROXY_PORT_LENGTH = 5
private const val RUSSIAN_BYPASS_ACCURACY_MESSAGE =
    "Auto-detection may be inaccurate."
private val RUSSIAN_BYPASS_PACKAGE_PREFIXES = listOf(
    "ru.",
    "com.yandex."
)
private val RUSSIAN_BYPASS_PACKAGE_NAMES = setOf(
    "ru.sberbankmobile",
    "ru.ozon.app.android",
    "ru.avito",
    "ru.vtb24.mobilebanking.android",
    "ru.tinkoff.mb"
)
