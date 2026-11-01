package org.olcbox.app.ui.activities

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Track touch in the stationary parent, before translating the sheet's scrollable children. */
@Composable
internal fun rememberSettingsSheetDragModifier(onDismiss: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val controller = remember { SettingsSheetDragController(scope) { currentDismiss() } }
    val density = LocalDensity.current
    controller.height = LocalWindowInfo.current.containerSize.height.toFloat()
    controller.distanceThreshold = with(density) { 64.dp.toPx() }
    controller.velocityThreshold = with(density) { 1000.dp.toPx() }
    val drag = rememberDraggableState { controller.dragBy(it) }
    return Modifier
        // This node must precede graphicsLayer: its coordinates must not move with the sheet.
        .pointerInput(controller) {
            awaitPointerEventScope {
                var previousY: Float? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull() ?: continue
                    if (!pointer.previousPressed && pointer.pressed) {
                        controller.begin()
                        previousY = pointer.position.y
                        controller.windowDelta = 0f
                    } else if (pointer.pressed) {
                        controller.windowDelta = pointer.position.y - (previousY ?: pointer.position.y)
                        previousY = pointer.position.y
                    } else {
                        previousY = null
                        controller.windowDelta = 0f
                    }
                }
            }
        }
        .nestedScroll(controller.nestedScroll)
        .draggable(
            state = drag,
            orientation = Orientation.Vertical,
            enabled = !controller.dismissing,
            onDragStopped = { controller.finish(it) }
        )
        .graphicsLayer { translationY = controller.offset }
}

@Stable
private class SettingsSheetDragController(
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit
) {
    var offset by mutableFloatStateOf(0f)
        private set
    var dismissing by mutableStateOf(false)
        private set
    var height = 1f
    var distanceThreshold = 1f
    var velocityThreshold = 1f
    var windowDelta = 0f
    private var settling: Job? = null

    fun begin() {
        if (!dismissing) settling?.cancel()
    }

    fun dragBy(delta: Float) {
        if (!dismissing) offset = (offset + delta).coerceIn(0f, height.coerceAtLeast(1f))
    }

    fun finish(velocity: Float) {
        if (dismissing || offset <= 0f || settling?.isActive == true) return
        val close = offset >= distanceThreshold || velocity >= velocityThreshold
        dismissing = close
        settling = scope.launch {
            animate(offset, if (close) height else 0f,
                animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
            if (close) onDismiss()
        }
    }

    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || offset <= 0f) return Offset.Zero
            if (dismissing) return available
            val physicalDelta = windowDelta.coerceAtMost(0f)
            val before = offset
            dragBy(physicalDelta)
            val fraction = if (physicalDelta == 0f) 1f else ((before - offset) / -physicalDelta).coerceIn(0f, 1f)
            return Offset(0f, available.y * fraction)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            // Local deltas can change as the child moves. The unconsumed fraction of the
            // stationary parent's touch delta is the physical motion to give to the sheet.
            val total = available.y + consumed.y.coerceAtLeast(0f)
            val fraction = if (total > 0f) (available.y / total).coerceIn(0f, 1f) else 0f
            dragBy(windowDelta.coerceAtLeast(0f) * fraction)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            return if (offset > 0f) { finish(available.y); available } else Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            finish(available.y)
            return available
        }
    }
}
