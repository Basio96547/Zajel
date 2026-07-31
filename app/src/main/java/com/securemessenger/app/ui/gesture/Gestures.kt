package com.securemessenger.app.ui.gesture

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Shared, reusable gesture primitives so every swipe/zoom interaction in the
 * app (swipe-to-reply, the full-screen image viewer, the voice-message
 * waveform scrubber) behaves consistently instead of each screen hand-rolling
 * its own pointerInput logic and threshold constants.
 */

/**
 * A horizontal drag that springs back to rest on release, firing
 * [onTriggered] once (per drag) when it passes [triggerPx]. Used for
 * swipe-to-reply: dragging a message bubble reveals a reply icon and commits
 * once you've dragged far enough, exactly like Telegram/WhatsApp.
 */
fun Modifier.swipeToTrigger(
    enabled: Boolean = true,
    triggerPx: Float = 140f,
    maxOffsetPx: Float = 200f,
    onProgress: (Float) -> Unit = {},
    onTriggered: () -> Unit
): Modifier = composed {
    var rawOffset by remember { mutableFloatStateOf(0f) }
    var triggeredThisDrag by remember { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(targetValue = rawOffset, label = "swipeOffset")

    this
        .graphicsLayer { translationX = animatedOffset }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragEnd = {
                    rawOffset = 0f
                    triggeredThisDrag = false
                    onProgress(0f)
                },
                onDragCancel = {
                    rawOffset = 0f
                    triggeredThisDrag = false
                    onProgress(0f)
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    val next = (rawOffset + dragAmount).coerceIn(-maxOffsetPx, maxOffsetPx)
                    rawOffset = next
                    onProgress(swipeProgress(next, triggerPx) * if (next < 0) -1f else 1f)
                    if (!triggeredThisDrag && abs(next) >= triggerPx) {
                        triggeredThisDrag = true
                        onTriggered()
                    }
                }
            )
        }
}

/** How far into a [swipeToTrigger] drag the reveal icon should fade/scale in, 0f..1f. */
fun swipeProgress(offsetPx: Float, triggerPx: Float = 140f): Float =
    (abs(offsetPx) / triggerPx).coerceIn(0f, 1f)

/**
 * Pinch-to-zoom + pan state with clamped scale and a reset — the gesture set
 * shared by every zoomable surface (currently the full-screen image viewer).
 */
class ZoomState(private val minScale: Float = 1f, private val maxScale: Float = 5f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    fun onGesture(pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        scale = newScale
        if (newScale <= minScale) {
            // Snapped back to 1x — recenter instead of leaving it panned
            // off-screen with nothing to scroll back with.
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += pan.x
            offsetY += pan.y
        }
    }

}

@Composable
fun rememberZoomState(minScale: Float = 1f, maxScale: Float = 5f): ZoomState =
    remember { ZoomState(minScale, maxScale) }

/** Applies [state]'s current scale/pan and listens for pinch/pan gestures to update it. */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ -> state.onGesture(pan, zoom) }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offsetX
        translationY = state.offsetY
    }

/**
 * A light scale-down while pressed — the tactile "the app noticed my touch"
 * feedback every tappable surface should have. Reads its own
 * [MutableInteractionSource] by default so it can be dropped onto any
 * `clickable`/button without wiring anything up.
 */
fun Modifier.pressScale(
    pressedScale: Float = 0.92f,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffectPressState(source) { pressed = it }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
private fun LaunchedEffectPressState(source: MutableInteractionSource, onPressed: (Boolean) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> onPressed(true)
                is PressInteraction.Release, is PressInteraction.Cancel -> onPressed(false)
            }
        }
    }
}
