package org.olcbox.app.ui.features.locations.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.util.parseEmojiAndName

@Composable
fun LocationRow(
    location: LocationItem,
    isSelected: Boolean,
    isLoading: Boolean,
    pingMs: Int?,
    isError: Boolean = false,
    settingsEnabled: Boolean = true,
    onSettingsClick: () -> Unit = {},
    onMoveRequested: (Int) -> Unit = {},
    onClick: () -> Unit
) {
    var isDragging by remember(location.storageId) { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(16.dp)
    val bgColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.tertiaryContainer
            isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        label = "locationRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.tertiary
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "locationRowBorder"
    )
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        label = "locationRowDragScale"
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) 10.dp else 0.dp,
        label = "locationRowDragElevation"
    )
    val borderWidth = if (isSelected || isDragging) 2.dp else 1.dp
    val textColor = MaterialTheme.colorScheme.onSurface
    val reorderThresholdPx = with(LocalDensity.current) { 44.dp.toPx() }
    val currentOnMoveRequested by rememberUpdatedState(onMoveRequested)

    val metadata = location.metadata
    val rawName = metadata?.name?.takeIf { it.isNotBlank() } ?: location.fullName
    val fallbackIcon = metadata?.icon?.takeIf { it.isNotBlank() }
        ?: metadata?.subscription?.icon?.takeIf { it.isNotBlank() }
        ?: ""
    val (emoji, parsedName) = parseEmojiAndName(rawName, fallbackIcon)
    val cleanName = parsedName.ifBlank { location.profile.displayName(location.config?.displayName().orEmpty()) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
            }
            .shadow(dragElevation, rowShape, clip = false)
            .clip(rowShape)
            .background(bgColor)
            .border(borderWidth, borderColor, rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        if (emoji.isNotEmpty()) {
            Text(text = emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanName,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = locationSubtitle(location),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when {
            isLoading -> {
                ShimmeringPingSkeleton()
            }

            pingMs != null -> {
                Text(
                    text = "$pingMs ms",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            isError -> {
                Text(
                    text = "Offline",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isDragging) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape
                )
                .pointerInput(location.storageId, reorderThresholdPx) {
                    var accumulatedDistance = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            accumulatedDistance = 0f
                            isDragging = true
                        },
                        onDragCancel = {
                            accumulatedDistance = 0f
                            isDragging = false
                        },
                        onDragEnd = {
                            accumulatedDistance = 0f
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedDistance += dragAmount.y
                            if (abs(accumulatedDistance) >= reorderThresholdPx) {
                                currentOnMoveRequested(if (accumulatedDistance > 0f) 1 else -1)
                                accumulatedDistance = 0f
                            }
                        }
                    )
                }
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = if (isDragging) {
                    "Reordering profile"
                } else {
                    "Hold and drag to reorder"
                },
                tint = if (isDragging) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        if (settingsEnabled) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        LocationSelectionIndicator(isSelected = isSelected)
    }
}

private fun locationSubtitle(location: LocationItem): String {
    val profile = location.profile
    if (!profile.isOlcRtc()) {
        return listOfNotNull(
            profile.typeLabel(),
            profile.localSocksPort?.let { port ->
                "SOCKS ${profile.localSocksHost ?: "127.0.0.1"}:$port"
            },
            location.metadata?.comment?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    val config = location.config
    val metadata = location.metadata
    val providerName = config?.providerName()
        ?: LocationConfig.providerDisplayName(LocationConfig.DEFAULT_BYPASS_PROVIDER)
    val transportName = config?.transportName()
        ?: LocationConfig.transportDisplayName(LocationConfig.DEFAULT_TRANSPORT)

    return listOfNotNull(
        providerName,
        transportName,
        metadata?.comment?.takeIf { it.isNotBlank() },
        metadata?.ip?.takeIf { it.isNotBlank() }?.let { "IP $it" },
        quotaText(metadata?.used, metadata?.available)
    ).joinToString(" · ")
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

@Composable
private fun LocationSelectionIndicator(isSelected: Boolean) {
    if (isSelected) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Selected location",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ShimmeringPingSkeleton() {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = -50f,
        targetValue = 150f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
        ),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 50f, 50f)
    )

    Box(
        modifier = Modifier
            .width(42.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(brush)
    )
}
