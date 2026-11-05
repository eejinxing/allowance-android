package com.allowance.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.allowance.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** Smooth curve through [points] with flat tangents at every point, so it never overshoots. */
internal fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y)
    for (i in 1 until points.size) {
        val a = points[i - 1]; val b = points[i]
        val mid = (a.x + b.x) / 2f
        cubicTo(mid, a.y, mid, b.y, b.x, b.y)
    }
}

internal fun DrawScope.drawGrownLine(path: Path, color: Color, progress: Float, width: Float) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    measure.getSegment(0f, measure.length * progress, segment, true)
    drawPath(segment, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Tiny area chart that draws itself in. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(1f, 1200, 150)
    val lineColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val pad = 6.dp.toPx()
        val points = values.mapIndexed { i, v ->
            Offset(if (values.size <= 1) 0f else i * size.width / (values.size - 1), pad + (size.height - pad * 2) * (1f - v / max))
        }
        if (points.isEmpty()) return@Canvas
        val line = smoothPath(points)
        val area = Path().apply { addPath(line); lineTo(points.last().x, size.height); lineTo(points.first().x, size.height); close() }
        clipRect(right = size.width * grow) { drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .55f), Color.Transparent))) }
        drawGrownLine(line, lineColor, grow, 2.2.dp.toPx())
    }
}

/**
 * Two smooth series, one point per column (a day). Tap or drag to pick a day: a soft band
 * drops from the self-paid line and a bubble shows what you paid yourself that day.
 */
@Composable
fun SpendingChart(self: List<Long>, family: List<Long>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, smooth: Boolean = true) {
    val grow = rememberGrow(1f, 1300)
    val highlight = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { highlight.animateTo(selected.toFloat(), spring(stiffness = Spring.StiffnessMediumLow)) }
    val measurer = rememberTextMeasurer()
    val olive = Color(0xFFD4CC00)
    val bubble = Color(0xFFFFF59A)
    val lineInk = MaterialTheme.colorScheme.onSurface
    val n = self.size.coerceAtLeast(1)
    val passes = if (smooth) 2 else 1
    val selfLine = softened(self, passes)
    val familyLine = softened(family, passes)
    fun pick(x: Float, width: Float) = (x / width * n).toInt().coerceIn(0, n - 1)
    Canvas(modifier.pointerInput(n) {
        detectTapGestures { onSelect(pick(it.x, size.width.toFloat())) }
    }.pointerInput(n) {
        detectHorizontalDragGestures { change, _ -> onSelect(pick(change.position.x, size.width.toFloat())) }
    }) {
        if (self.isEmpty()) return@Canvas
        val max = maxOf(selfLine.maxOrNull() ?: 0L, familyLine.maxOrNull() ?: 0L, 1L).toFloat()
        val top = 54.dp.toPx(); val bottom = 16.dp.toPx()
        val chartWidth = size.width
        val col = chartWidth / n
        fun y(v: Long) = top + (size.height - top - bottom) * (.92f - .8f * v / max)
        fun points(values: List<Long>) = buildList {
            add(Offset(0f, y(values.first())))
            values.forEachIndexed { i, v -> add(Offset((i + .5f) * col, y(v))) }
            add(Offset(chartWidth, y(values.last())))
        }
        val h = highlight.value.coerceIn(0f, (n - 1).toFloat())
        val i0 = h.toInt(); val i1 = (i0 + 1).coerceAtMost(n - 1); val t = h - i0
        val x = (h + .5f) * col
        val markerY = y(selfLine[i0]) + (y(selfLine[i1]) - y(selfLine[i0])) * t
        val band = minOf(col * .7f, 30.dp.toPx())
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFFF27A).copy(alpha = .75f), Color(0xFFFFF27A).copy(alpha = 0f)), startY = markerY, endY = size.height),
            Offset(x - band / 2, markerY), Size(band, size.height - markerY))
        val familyPath = smoothPath(points(familyLine)); val selfPath = smoothPath(points(selfLine))
        val area = Path().apply { addPath(familyPath); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        clipRect(right = size.width * grow) { drawPath(area, Brush.verticalGradient(listOf(lineInk.copy(alpha = .06f), Color.Transparent))) }
        drawGrownLine(familyPath, lineInk, grow, 2.2.dp.toPx())
        drawGrownLine(selfPath, olive, grow, 2.6.dp.toPx())
        drawCircle(Color.White, 8.dp.toPx(), Offset(x, markerY)); drawCircle(olive, 8.dp.toPx(), Offset(x, markerY), style = Stroke(3.dp.toPx()))
        val label = measurer.measure(cash(self.getOrElse(selected) { 0L }).removeSuffix(".00"), TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink))
        val w = label.size.width + 26.dp.toPx(); val bh = label.size.height + 16.dp.toPx()
        val left = (x - w / 2).coerceIn(0f, size.width - w)
        val bubbleTop = (markerY - bh - 16.dp.toPx()).coerceAtLeast(0f)
        drawRoundRect(bubble, Offset(left, bubbleTop), Size(w, bh), CornerRadius(bh / 2))
        drawText(label, topLeft = Offset(left + 13.dp.toPx(), bubbleTop + 8.dp.toPx()))
    }
}

/** Concentric goal rings that fill counter-clockwise from the top. */
@Composable
fun GoalRings(progress: List<Pair<Color, Float>>, modifier: Modifier = Modifier) {
    val grows = progress.mapIndexed { i, (_, value) -> rememberGrow(value.coerceIn(0f, 1f), 1100, i * 160) }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
    Canvas(modifier.aspectRatio(1f)) {
        val stroke = (if (progress.size <= 3) 18.dp else 15.dp).toPx()
        val gap = stroke + 10.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        progress.forEachIndexed { i, (color, _) ->
            val radius = size.minDimension / 2 - stroke / 2 - 2.dp.toPx() - i * gap
            if (radius <= stroke) return@forEachIndexed
            val topLeft = Offset(center.x - radius, center.y - radius)
            drawCircle(track, radius, center, style = Stroke(stroke))
            val sweep = -360f * grows[i]
            if (grows[i] > 0.002f) {
                drawArc(color, -90f, sweep, false, topLeft, Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                val angle = Math.toRadians((-90f + sweep).toDouble())
                val knob = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                drawCircle(Color.White, stroke * .26f, knob)
                drawCircle(Ink.copy(alpha = .35f), stroke * .26f, knob, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/**
 * The tiger mascot artwork. Pops in, bobs and gives a little waving wiggle a few times
 * when it appears (finite, so tests still go idle); tap to replay.
 */
@Composable
fun TigerMascot(modifier: Modifier = Modifier) {
    var replay by remember { mutableIntStateOf(0) }
    val reduce = LocalReduceMotion.current
    val pop = remember { Animatable(if (reduce) 1f else .6f) }
    val bob = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    // The tiger's greeting is this page's one authored moment; it stays still when motion is reduced.
    LaunchedEffect(replay) {
        if (reduce) return@LaunchedEffect
        pop.snapTo(.6f)
        pop.animateTo(1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(replay) {
        if (reduce) return@LaunchedEffect
        wave.animateTo(1f, repeatable(4, tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse))
        wave.snapTo(0f)
    }
    LaunchedEffect(replay) {
        if (reduce) return@LaunchedEffect
        bob.animateTo(1f, repeatable(4, tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse)); bob.snapTo(0f)
    }
    Box(modifier.aspectRatio(.78f).pointerInput(Unit) { detectTapGestures { replay++ } }, contentAlignment = Alignment.BottomCenter) {
        // Soft contact shadow that shrinks as the tiger lifts.
        Canvas(Modifier.fillMaxWidth(.62f).height(18.dp)) {
            val lift = bob.value
            val w = size.width * (.9f - lift * .12f)
            // Two stacked ellipses read as a soft contact shadow without a blur pass.
            drawOval(Color(0xFF6B5A00).copy(alpha = .10f - lift * .03f), Offset((size.width - w) / 2, 0f), Size(w, size.height))
            drawOval(Color(0xFF6B5A00).copy(alpha = .10f - lift * .03f), Offset((size.width - w * .6f) / 2, size.height * .2f), Size(w * .6f, size.height * .6f))
        }
        Image(painterResource(R.drawable.tiger_mascot), "Budget assistant tiger", Modifier.fillMaxSize().padding(bottom = 8.dp).graphicsLayer {
            scaleX = pop.value; scaleY = pop.value
            translationY = -bob.value * 10.dp.toPx()
            rotationZ = (wave.value - .5f) * 5f * (if (wave.isRunning) 1f else 0f)
            transformOrigin = TransformOrigin(.5f, 1f)
        }, contentScale = ContentScale.Fit)
    }
}

/** Dashed rounded outline used by "add" tiles. */
fun Modifier.dashedBorder(color: Color, radius: Dp = 24.dp): Modifier = drawBehind {
    drawRoundRect(color, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(radius.toPx()),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))))
}

/** Light [1,2,1] smoothing, applied twice, so sparse days read as gentle waves instead of spikes. */
private fun softened(values: List<Long>, passes: Int = 2): List<Long> {
    fun pass(v: List<Long>) = v.indices.map { i -> (v.getOrElse(i - 1) { v[i] } + 2 * v[i] + v.getOrElse(i + 1) { v[i] }) / 4 }
    var result = values
    if (values.size >= 3) repeat(passes) { result = pass(result) }
    return result
}
