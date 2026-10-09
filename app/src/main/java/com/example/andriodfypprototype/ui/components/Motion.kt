package com.example.andriodfypprototype.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.ui.theme.WrMotion

/** Haptics routed through one place so the operator's "haptics off" setting is honoured. */
class Haptics(private val h: HapticFeedback) {
    private fun go(t: HapticFeedbackType) { if (AppState.hapticProximity) h.performHapticFeedback(t) }
    fun tap() = go(HapticFeedbackType.ContextClick)
    fun tick() = go(HapticFeedbackType.SegmentTick)
    fun confirm() = go(HapticFeedbackType.Confirm)
    fun reject() = go(HapticFeedbackType.Reject)
    fun toggle(on: Boolean) = go(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun threshold() = go(HapticFeedbackType.GestureThresholdActivate)
    fun heavy() = go(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics(h) }
}

/**
 * Click with a spring press-scale. The scale is applied in the graphics layer so pressing
 * never triggers relayout, and the ripple is suppressed: on cream surfaces the grey ripple
 * reads as dirt, and the scale already acknowledges the touch.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.97f,
    haptic: Boolean = false,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) scaleTo else 1f, WrMotion.press(), label = "press")
    val haptics = rememberHaptics()
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptic) haptics.tap()
            onClick()
        }
}

/** Row-style press: a soft tint instead of scaling, for full-width list rows. */
fun Modifier.rowPress(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val a by animateFloatAsState(if (pressed) 0.05f else 0f, tween(120), label = "row")
    this
        .drawWithContent {
            drawContent()
            if (a > 0f) drawRect(Color(0xFF1B2A21).copy(alpha = a))
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** Skeleton shimmer for content that is still being produced. */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                start = Offset(w * x - w * 0.4f, 0f), end = Offset(w * x, size.height)
            )
        )
    }
}

/** Fades and lifts content in once, staggered by index. */
fun Modifier.enter(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40L * index.coerceAtMost(8))
        a.animateTo(1f, tween(420, easing = WrMotion.Decelerate))
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 18.dp2px(density)
    }
}

private fun Int.dp2px(density: Float) = this * density
