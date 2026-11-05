package com.allowance.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * True when the system "Remove animations" / animator scale is 0. Motion helpers then show
 * final values immediately; meaningful state (colors, selection) still changes.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Confident arrival curve: fast start, long gentle settle. */
val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Animates 0 → [target] the first time it shows, then eases to every later target. Finite, so tests still go idle. */
@Composable
fun rememberGrow(target: Float, durationMs: Int = 800, delayMs: Int = 0): Float {
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) progress.snapTo(target) else progress.animateTo(target, tween(durationMs, delayMs, EaseOutExpo))
    }
    return progress.value
}

/** Money that counts up to its value; shows the exact amount once the animation settles. */
@Composable
fun rememberCountUp(target: Long, durationMs: Int = 800): Long {
    if (LocalReduceMotion.current) return target
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) { progress.animateTo(target.toFloat(), tween(durationMs, easing = EaseOutExpo)) }
    return if (progress.isRunning) progress.value.toLong() else target
}

/** Short fade-and-rise as a list builds; capped so the last item lands quickly. */
fun Modifier.staggerIn(index: Int, stepMs: Int = 40): Modifier = composed {
    if (LocalReduceMotion.current) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(320, index.coerceIn(0, 6) * stepMs, EaseOutExpo)) }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}

/** Clickable that dips slightly while pressed: quick, no overshoot. */
fun Modifier.pressable(enabled: Boolean = true, role: Role? = Role.Button, onClickLabel: String? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .96f else 1f,
        spring(dampingRatio = .85f, stiffness = Spring.StiffnessMediumLow * 2), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/** Lets an item inside a padded list span the full screen width. */
fun Modifier.fullBleed(inset: Dp = 16.dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

val HeroTop = androidx.compose.ui.graphics.Color(0xFFFFDE4D)

/**
 * Signature header wash: a lemon fade into [end], warmed by an apricot glow in the top-right
 * and lifted by a soft light in the top-left, so headers read rich rather than flat.
 */
fun Modifier.heroBackground(dark: Boolean, end: androidx.compose.ui.graphics.Color, top: androidx.compose.ui.graphics.Color = HeroTop): Modifier =
    drawBehind {
        val base = if (dark) androidx.compose.ui.graphics.lerp(top, androidx.compose.ui.graphics.Color.Black, .68f) else top
        drawRect(Brush.verticalGradient(0f to base, .55f to androidx.compose.ui.graphics.lerp(base, end, .55f), 1f to end))
        drawRect(Brush.radialGradient(listOf((if (dark) androidx.compose.ui.graphics.Color(0xFF7A4A10) else androidx.compose.ui.graphics.Color(0xFFFFB648)).copy(alpha = .42f),
            androidx.compose.ui.graphics.Color.Transparent), center = Offset(size.width * .96f, size.height * .02f), radius = size.width * .78f))
        drawRect(Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color.White.copy(alpha = if (dark) .04f else .5f), androidx.compose.ui.graphics.Color.Transparent),
            center = Offset(size.width * .1f, size.height * .22f), radius = size.width * .6f))
    }
