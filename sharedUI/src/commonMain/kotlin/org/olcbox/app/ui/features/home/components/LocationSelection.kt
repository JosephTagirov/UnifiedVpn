package org.olcbox.app.ui.features.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.olcbox.app.ui.localization.AppText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: (targetLocationIds: List<String>) -> Unit,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onMoveLocation: (String, Int) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .toList()
        val customLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

        if (locations.isEmpty()) {
            RelaySetupCard(
                onAddSubscriptionClick = onAddSubscriptionClick,
                onAddLocationClick = onAddLocationClick
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            subscriptionGroups.forEachIndexed { index, group ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SubscriptionGroupHeader(
                            locations = group,
                            modifier = Modifier.weight(1f)
                        )

                        val groupIds = group.map { it.storageId }
                        val isGroupRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in groupIds }

                        RefreshButton(
                            isRefreshing = isGroupRefreshing,
                            onClick = { onRefreshClick(groupIds) },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    key(group.first().subscriptionGroupKey()) {
                        ReorderableLocationGroup(
                            locations = group,
                            selectedLocationId = selectedLocationId,
                            pingsState = pingsState,
                            onLocationSelected = onLocationSelected,
                            onLocationSettingsClick = onLocationSettingsClick,
                            onMoveLocation = onMoveLocation
                        )
                    }
                }
            }

            if (customLocations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LocationGroupHeader(
                            title = "Profiles",
                            modifier = Modifier.weight(1f)
                        )

                        // 2. Вычисляем состояние загрузки только для кастомных локаций
                        val customIds = customLocations.map { it.storageId }
                        val isCustomRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in customIds }

                        RefreshButton(
                            isRefreshing = isCustomRefreshing,
                            onClick = { onRefreshClick(customIds) },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    key(CUSTOM_PROFILE_GROUP_KEY) {
                        ReorderableLocationGroup(
                            locations = customLocations,
                            selectedLocationId = selectedLocationId,
                            pingsState = pingsState,
                            onLocationSelected = onLocationSelected,
                            onLocationSettingsClick = onLocationSettingsClick,
                            onMoveLocation = onMoveLocation
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = onAddLocationClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add olcRTC location",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (subscriptionLocations.isEmpty()) {
                FilledTonalButton(
                    onClick = onAddSubscriptionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add subscription",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Add VPN profile",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )

        SetupActionRow(
            title = "Add subscription",
            subtitle = "Scan QR, paste URI, or import config file",
            icon = Icons.Outlined.QrCodeScanner,
            prominent = true,
            onClick = onAddSubscriptionClick
        )

        SetupActionRow(
            title = "Create olcRTC location",
            subtitle = "Enter room, key, provider, and transport",
            icon = Icons.Outlined.Add,
            onClick = onAddLocationClick
        )
    }
}

@Composable
private fun SetupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val borderColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val title = first?.subscriptionTitle().orEmpty().ifBlank { "Subscriptions" }
    val details = first?.subscriptionDetails()

    Column(modifier = modifier.padding(start = 4.dp, top = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        if (!details.isNullOrBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReorderableLocationGroup(
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onMoveLocation: (String, Int) -> Unit
) {
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val itemExtentPx = with(LocalDensity.current) {
        (PROFILE_ROW_HEIGHT + PROFILE_ROW_SPACING).toPx()
    }
    val listHeight = PROFILE_ROW_HEIGHT * locations.size +
        PROFILE_ROW_SPACING * (locations.size - 1).coerceAtLeast(0)

    fun settleDraggedRow() {
        val settlingId = draggedId ?: return
        settleJob?.cancel()
        settleJob = scope.launch {
            val animation = Animatable(dragOffsetY)
            animation.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 140)
            ) {
                if (draggedId == settlingId) {
                    dragOffsetY = value
                }
            }
            if (draggedId == settlingId) {
                dragOffsetY = 0f
                draggedIndex = -1
                draggedId = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(listHeight),
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(PROFILE_ROW_SPACING)
    ) {
        items(
            items = locations,
            key = { location -> location.storageId }
        ) { location ->
            val isDragging = draggedId == location.storageId
            val placementModifier = if (isDragging) {
                Modifier.zIndex(1f)
            } else {
                Modifier
                    .animateItem()
                    .zIndex(0f)
            }

            LocationSelectorRow(
                modifier = placementModifier,
                location = location,
                selectedLocationId = selectedLocationId,
                pingsState = pingsState,
                isDragging = isDragging,
                dragOffsetY = if (isDragging) dragOffsetY else 0f,
                onLocationSelected = onLocationSelected,
                onLocationSettingsClick = onLocationSettingsClick,
                onDragStart = {
                    settleJob?.cancel()
                    draggedId = location.storageId
                    draggedIndex = locations.indexOfFirst {
                        it.storageId == location.storageId
                    }
                    dragOffsetY = 0f
                },
                onDrag = { deltaY ->
                    val activeId = draggedId ?: return@LocationSelectorRow
                    if (draggedIndex !in locations.indices) return@LocationSelectorRow

                    var nextOffset = dragOffsetY + deltaY
                    var nextIndex = draggedIndex
                    val crossingThreshold = itemExtentPx / 2f

                    while (nextOffset > crossingThreshold && nextIndex < locations.lastIndex) {
                        onMoveLocation(activeId, 1)
                        nextIndex += 1
                        nextOffset -= itemExtentPx
                    }
                    while (nextOffset < -crossingThreshold && nextIndex > 0) {
                        onMoveLocation(activeId, -1)
                        nextIndex -= 1
                        nextOffset += itemExtentPx
                    }

                    val minOffset = if (nextIndex == 0) {
                        -itemExtentPx * EDGE_DRAG_RESISTANCE
                    } else {
                        -crossingThreshold
                    }
                    val maxOffset = if (nextIndex == locations.lastIndex) {
                        itemExtentPx * EDGE_DRAG_RESISTANCE
                    } else {
                        crossingThreshold
                    }
                    draggedIndex = nextIndex
                    dragOffsetY = nextOffset.coerceIn(minOffset, maxOffset)
                },
                onDragCancel = ::settleDraggedRow,
                onDragEnd = ::settleDraggedRow
            )
        }
    }
}

@Composable
private fun LocationSelectorRow(
    modifier: Modifier = Modifier,
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    isDragging: Boolean,
    dragOffsetY: Float,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragCancel: () -> Unit,
    onDragEnd: () -> Unit
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    LocationRow(
        modifier = modifier,
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        isDragging = isDragging,
        dragOffsetY = dragOffsetY,
        settingsEnabled = true,
        onSettingsClick = {
            onLocationSettingsClick(location.storageId)
        },
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragCancel = onDragCancel,
        onDragEnd = onDragEnd,
        onClick = {
            onLocationSelected(location.storageId)
        }
    )
}

private fun PingsState.pingFor(locationId: String): Int? {
    return when (this) {
        PingsState.Idle -> null

        is PingsState.Loading -> {
            if (currentPings.containsKey(locationId)) {
                currentPings[locationId]
            } else {
                lastPings?.get(locationId)
            }
        }

        is PingsState.Success -> {
            pings[locationId]
        }

        is PingsState.Error -> {
            lastPings?.get(locationId)
        }
    }
}

private fun PingsState.isChecking(locationId: String): Boolean {
    return this is PingsState.Loading && locationId in pendingLocationIds
}

private fun PingsState.isOffline(locationId: String): Boolean {
    return when (this) {
        PingsState.Idle -> false

        is PingsState.Loading -> {
            currentPings.containsKey(locationId) && currentPings[locationId] == null
        }

        is PingsState.Success -> {
            pings.containsKey(locationId) && pings[locationId] == null
        }

        is PingsState.Error -> false
    }
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.name?.takeIf { it.isNotBlank() } ?: "Subscriptions"
    ).joinToString(" ")
}

private fun LocationItem.subscriptionDetails(): String? {
    val subscription = metadata?.subscription ?: return null

    return listOfNotNull(
        quotaText(subscription.used, subscription.available),
        subscription.refresh?.takeIf { it.isNotBlank() }?.let { "Refresh $it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private val PROFILE_ROW_HEIGHT = 76.dp
private val PROFILE_ROW_SPACING = 12.dp
private const val EDGE_DRAG_RESISTANCE = 0.2f
private const val CUSTOM_PROFILE_GROUP_KEY = "custom-profiles"
