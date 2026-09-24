package org.olcbox.app.ui.activities

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.ui.localization.AppText as Text
import org.olcbox.app.ui.localization.androidUiText
import org.olcbox.app.ui.settings.AndroidAppearanceSettingsStore
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.notificationProfileAttemptResult
import org.olcbox.app.vpn.notificationProfileRequestBusy
import org.olcbox.app.vpn.notificationSafeProfileName
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.util.Locale
import kotlin.math.max

class VpnProfileChooserActivity : ComponentActivity() {
    private val repository by lazy {
        AndroidLocationsRepository.get(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != OlcboxVpnActions.ACTION_OPEN_PROFILE_CHOOSER || isDeviceLocked()) {
            finishAndRemoveTask()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.decorView.filterTouchesWhenObscured = true
        window.attributes = window.attributes.apply {
            dimAmount = 0.38f
            gravity = Gravity.CENTER
        }
        setFinishOnTouchOutside(true)

        val appearance = AndroidAppearanceSettingsStore(this).loadNow()
        setContent {
            AppTheme(
                useDynamicColor = false,
                themeMode = appearance.theme,
                language = appearance.language
            ) {
                val status by OlcboxVpnState.status.collectAsState()
                val session by OlcboxVpnState.session.collectAsState()
                var profiles by remember { mutableStateOf<List<NotificationProfileChoice>?>(null) }
                var activeProfileId by remember { mutableStateOf<String?>(null) }
                var pendingProfileId by remember { mutableStateOf<String?>(null) }
                var stopRequested by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                val profileRevision by repository.changes.collectAsState()
                LaunchedEffect(profileRevision) {
                    runCatching { repository.getBundle() }
                        .onSuccess { bundle ->
                            profiles = bundle.locations
                                .filter { it.isComplete() }
                                .map { entry ->
                                    NotificationProfileChoice(
                                        storageId = entry.storageId,
                                        name = notificationSafeProfileName(entry.displayName()),
                                        type = entry.profile.typeLabel()
                                    )
                                }
                            activeProfileId = OlcboxVpnState.session.value.activeProfileStorageId
                                ?: bundle.activeLocationId
                        }
                        .onFailure {
                            if (it is CancellationException) throw it
                            profiles = emptyList()
                        }
                }

                LaunchedEffect(session.activeProfileStorageId) {
                    session.activeProfileStorageId?.let { activeProfileId = it }
                }
                val busy = pendingProfileId != null || notificationProfileRequestBusy(status)
                val switchingProfileId = pendingProfileId ?: session.activeProfileStorageId.takeIf {
                    status is VpnStatus.Connecting || status is VpnStatus.Reconnecting
                }
                val retryProfile = profiles?.firstOrNull {
                    it.storageId == session.failedProfileStorageId
                }
                val requestProfile: (NotificationProfileChoice, Boolean) -> Unit = { profile, retry ->
                    if (pendingProfileId == null &&
                        !notificationProfileRequestBusy(OlcboxVpnState.status.value) &&
                        OlcboxVpnState.status.value !is VpnStatus.Disconnected &&
                        (retry || profile.storageId != activeProfileId)
                    ) {
                        pendingProfileId = profile.storageId
                        stopRequested = false
                        val afterGeneration = OlcboxVpnState.session.value.connectionGeneration
                        scope.launch {
                            try {
                                startService(
                                    Intent(this@VpnProfileChooserActivity, OlcboxVpnService::class.java)
                                        .setAction(
                                            if (retry) OlcboxVpnActions.ACTION_RETRY_PROFILE
                                            else OlcboxVpnActions.ACTION_APPLY_SELECTED_PROFILE
                                        )
                                        .putExtra(OlcboxVpnActions.EXTRA_PROFILE_STORAGE_ID, profile.storageId)
                                )
                                if (!awaitProfileSwitchResult(profile.storageId, afterGeneration) &&
                                    !stopRequested &&
                                    OlcboxVpnState.status.value !is VpnStatus.Stopping &&
                                    OlcboxVpnState.status.value !is VpnStatus.Disconnected
                                ) {
                                    Toast.makeText(
                                        this@VpnProfileChooserActivity,
                                        androidUiText("Profile switch failed"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                if (!stopRequested) {
                                    Toast.makeText(
                                        this@VpnProfileChooserActivity,
                                        androidUiText("Profile switch failed"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } finally {
                                pendingProfileId = null
                            }
                        }
                    }
                }

                VpnProfileChooserContent(
                    status = status,
                    session = session,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    switchingProfileId = switchingProfileId,
                    retryProfile = retryProfile,
                    requestInProgress = busy,
                    appIconResource = applicationInfo.icon,
                    onOpenApp = ::openMainApp,
                    onDismiss = ::finish,
                    onStop = {
                        stopRequested = true
                        startService(
                            Intent(this@VpnProfileChooserActivity, OlcboxVpnService::class.java)
                                .setAction(OlcboxVpnActions.ACTION_STOP_VPN)
                        )
                    },
                    onProfileSelected = { requestProfile(it, false) },
                    onReconnect = { retryProfile?.let { requestProfile(it, true) } }
                )
            }
        }

        window.decorView.post {
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDeviceLocked()) finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscuredFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscuredFlags != 0) return false
        return super.dispatchTouchEvent(event)
    }

    private fun isDeviceLocked(): Boolean {
        return (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    }

    private fun openMainApp() {
        if (isDeviceLocked()) {
            finish()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.takeIf { it.component?.packageName == packageName }
        val opened = launchIntent != null && runCatching {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(launchIntent)
        }.isSuccess
        if (opened) {
            finish()
        } else {
            Toast.makeText(this, androidUiText("Could not open Unified VPN"), Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun awaitProfileSwitchResult(
        targetProfileId: String,
        afterGeneration: Long
    ): Boolean {
        val waitStartedAt = SystemClock.elapsedRealtime()
        return withTimeoutOrNull(PROFILE_SWITCH_WAIT_TIMEOUT_MS) {
            while (true) {
                val currentSession = OlcboxVpnState.session.value
                notificationProfileAttemptResult(
                    targetProfileId = targetProfileId,
                    afterGeneration = afterGeneration,
                    generation = currentSession.connectionGeneration,
                    connectedGeneration = currentSession.connectedGeneration,
                    status = currentSession.status,
                    activeProfileId = currentSession.activeProfileStorageId,
                    connectedProfileId = currentSession.connectedProfileStorageId,
                    failedProfileId = currentSession.failedProfileStorageId,
                    failedGeneration = currentSession.failedProfileGeneration
                )?.let { return@withTimeoutOrNull it }
                if (currentSession.connectionGeneration <= afterGeneration &&
                    SystemClock.elapsedRealtime() - waitStartedAt >= PROFILE_SWITCH_ACCEPT_TIMEOUT_MS
                ) {
                    return@withTimeoutOrNull false
                }
                delay(PROFILE_SWITCH_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    private companion object {
        const val PROFILE_SWITCH_POLL_MS = 100L
        const val PROFILE_SWITCH_ACCEPT_TIMEOUT_MS = 10_000L
        const val PROFILE_SWITCH_WAIT_TIMEOUT_MS = 4 * 60 * 1_000L
    }
}

private data class NotificationProfileChoice(
    val storageId: String,
    val name: String,
    val type: String
)

@Composable
private fun VpnProfileChooserContent(
    status: VpnStatus,
    session: org.olcbox.app.vpn.service.VpnSessionSnapshot,
    profiles: List<NotificationProfileChoice>?,
    activeProfileId: String?,
    switchingProfileId: String?,
    retryProfile: NotificationProfileChoice?,
    requestInProgress: Boolean,
    appIconResource: Int,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onProfileSelected: (NotificationProfileChoice) -> Unit,
    onReconnect: () -> Unit
) {
    val dialogMaxHeight = (LocalConfiguration.current.screenHeightDp.dp - 24.dp)
        .coerceAtLeast(280.dp)
    val closeDescription = LocalContext.current.androidUiText("Close")
    val openAppDescription = LocalContext.current.androidUiText("Open Unified VPN window")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = dialogMaxHeight),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp
        ) {
            LazyColumn(modifier = Modifier.padding(vertical = 12.dp)) {
            item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenApp, modifier = Modifier.size(48.dp)) {
                    Image(
                        painter = painterResource(appIconResource),
                        contentDescription = openAppDescription,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "Unified VPN",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    MaterialText(
                        text = session.activeProfileName.ifBlank { statusLabel(status) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = closeDescription
                    )
                }
            }

            ConnectionMetrics(status = status, session = session)

            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                text = "Profiles",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }

            when {
                profiles == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                profiles.isEmpty() -> item {
                    Text(
                        text = "No profiles",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> items(profiles, key = { it.storageId }) { profile ->
                    ProfileChoiceRow(
                        profile = profile,
                        selected = profile.storageId == activeProfileId,
                        switching = profile.storageId == switchingProfileId,
                        enabled = !requestInProgress && status !is VpnStatus.Disconnected,
                        onClick = { onProfileSelected(profile) }
                    )
                }
            }

            item {
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            if (retryProfile != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Connection failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    MaterialText(
                        text = retryProfile.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedButton(
                        onClick = onReconnect,
                        enabled = !requestInProgress && status !is VpnStatus.Disconnected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Reconnect")
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = status !is VpnStatus.Disconnected && status !is VpnStatus.Stopping,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Stop")
                }
            }
            }
            }
        }
    }
}

@Composable
private fun ConnectionMetrics(
    status: VpnStatus,
    session: org.olcbox.app.vpn.service.VpnSessionSnapshot
) {
    var nowElapsedRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.connectedAtElapsedRealtimeMs) {
        while (session.connectedAtElapsedRealtimeMs != null) {
            nowElapsedRealtime = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val duration = session.connectedAtElapsedRealtimeMs?.let { startedAt ->
        formatDuration(max(0L, nowElapsedRealtime - startedAt))
    } ?: "—"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is VpnStatus.Connected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricValue("Duration", duration, Modifier.weight(1f))
            MetricValue("Sent", formatBytes(session.sentBytes), Modifier.weight(1f))
            MetricValue("Received", formatBytes(session.receivedBytes), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        MaterialText(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProfileChoiceRow(
    profile: NotificationProfileChoice,
    selected: Boolean,
    switching: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected || switching) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (switching) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        } else {
            RadioButton(
                selected = selected,
                onClick = if (enabled && !selected) onClick else null
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            MaterialText(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            MaterialText(
                text = profile.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun statusLabel(status: VpnStatus): String = when (status) {
    VpnStatus.Connected -> "Connected"
    VpnStatus.Connecting -> "Connecting..."
    VpnStatus.Reconnecting -> "Reconnecting..."
    VpnStatus.Stopping -> "Stopping..."
    VpnStatus.Disconnected -> "Disconnected"
    is VpnStatus.Error -> "Connection failed"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatBytes(value: Long?): String {
    if (value == null || value < 0L) return "—"
    if (value < 1_024L) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unitIndex = -1
    while (amount >= 1_024.0 && unitIndex < units.lastIndex) {
        amount /= 1_024.0
        unitIndex++
    }
    return String.format(Locale.ROOT, if (amount >= 100) "%.0f %s" else "%.1f %s", amount, units[unitIndex])
}
