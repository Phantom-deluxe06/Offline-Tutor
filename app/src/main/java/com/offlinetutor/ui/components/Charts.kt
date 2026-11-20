package com.offlinetutor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
private fun rememberReveal(key: Any?): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    return a.value
}

/** Accuracy line (0..1) with a gradient area, drawn left-to-right on first show; dashed 70% target. */
@Composable
fun LineChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val target = MaterialTheme.colorScheme.secondary
    val reveal = rememberReveal(values)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width
            val h = size.height
            for (i in 0..4) {
                val y = h - h * i / 4f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
            }
            drawLine(target, Offset(0f, h * 0.3f), Offset(w, h * 0.3f), strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            if (values.isEmpty()) return@Canvas
            val inset = 10.dp.toPx() // keep 0% / 100% dots fully visible
            val step = if (values.size == 1) 0f else (w - 2 * inset) / (values.size - 1)
            fun pt(i: Int) = Offset(
                if (values.size == 1) w / 2 else inset + i * step,
                inset + (1f - values[i].coerceIn(0f, 1f)) * (h - 2 * inset),
            )
            val line = Path()
            val area = Path()
            values.indices.forEach { i ->
                val p = pt(i)
                if (i == 0) { line.moveTo(p.x, p.y); area.moveTo(p.x, h); area.lineTo(p.x, p.y) } else { line.lineTo(p.x, p.y); area.lineTo(p.x, p.y) }
            }
            area.lineTo(pt(values.lastIndex).x, h); area.close()
            clipRect(right = w * reveal) {
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f))))
                drawPath(line, color, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                values.indices.forEach { i ->
                    drawCircle(Color.White, 9f, pt(i))
                    drawCircle(color, 6f, pt(i))
                }
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (labels.size > 1) Text(labels.last(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

data class Bar(val label: String, val value: Float, val caption: String, val color: Color? = null)

/** Horizontal rounded bars (0..1) that grow in, staggered. */
@Composable
fun BarList(bars: List<Bar>, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        bars.forEachIndexed { i, b ->
            val a = remember(b) { Animatable(0f) }
            LaunchedEffect(b) { a.animateTo(b.value.coerceIn(0f, 1f), tween(800, delayMillis = i * 90, easing = FastOutSlowInEasing)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val r = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    if (a.value > 0f) drawRoundRect(b.color ?: fill, size = Size(maxOf(size.height, size.width * a.value), size.height), cornerRadius = r)
                }
                Spacer(Modifier.width(10.dp))
                Text(b.caption, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 7 vertical rounded bars (counts per day), today highlighted. */
@Composable
fun WeeklyBars(counts: List<Int>, labels: List<String>, highlight: Color, modifier: Modifier = Modifier) {
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { i, c ->
            val target = c.toFloat() / max
            val v by animateFloatAsState(target, tween(700, delayMillis = i * 60, easing = FastOutSlowInEasing), label = "wb$i")
            val today = i == counts.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (c > 0) "$c" else "", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Box(Modifier.width(22.dp).height(90.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.width(22.dp).fillMaxHeight(maxOf(0.04f, v))
                            .background(if (today) highlight else highlight.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall,
                    color = if (today) highlight else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Step chart of difficulty (1..3) for recent questions; dots coloured by right/wrong. */
@Composable
fun DifficultyTrail(levels: List<Int>, correct: List<Boolean>, colors: List<Color>, ok: Color, bad: Color, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val reveal = rememberReveal(levels)
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val h = size.height
        val w = size.width
        fun y(level: Int) = h - (level - 0.5f) / 3f * h
        for (l in 1..3) drawLine(colors[l - 1].copy(alpha = 0.18f), Offset(0f, y(l)), Offset(w, y(l)), strokeWidth = 22f, cap = StrokeCap.Round)
        if (levels.isEmpty()) return@Canvas
        val step = if (levels.size == 1) 0f else w / (levels.size - 1)
        val path = Path()
        levels.forEachIndexed { i, l ->
            val x = i * step
            if (i == 0) path.moveTo(x, y(l)) else { path.lineTo(x, y(levels[i - 1])); path.lineTo(x, y(l)) }
        }
        clipRect(right = w * reveal) {
            drawPath(path, line.copy(alpha = 0.6f), style = Stroke(width = 3f))
            levels.forEachIndexed { i, l ->
                drawCircle(Color.White, 10f, Offset(i * step, y(l)))
                drawCircle(if (correct.getOrElse(i) { false }) ok else bad, 7f, Offset(i * step, y(l)))
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
