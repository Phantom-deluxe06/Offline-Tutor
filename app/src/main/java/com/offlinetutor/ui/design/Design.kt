package com.offlinetutor.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlinetutor.ui.theme.LocalExtras
import com.offlinetutor.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Paper background: warm notebook page with ruled lines, a margin line and paper grain.
// Drawn procedurally (no image asset), so it is crisp at any density and costs no APK size.
// ---------------------------------------------------------------------------

private class Speck(val x: Float, val y: Float, val r: Float)

@Composable
fun PaperBackground(modifier: Modifier = Modifier, ruled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val ex = LocalExtras.current
    val specks = remember {
        val rnd = Random(7)
        List(900) { Speck(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.4f) }
    }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(ex.paper)
                for (s in specks) drawCircle(ex.speck, s.r, Offset(s.x * size.width, s.y * size.height))
                if (ruled) {
                    val gap = 34.dp.toPx()
                    var y = gap * 2.2f
                    while (y < size.height) {
                        drawLine(ex.paperLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.2f)
                        y += gap
                    }
                    val mx = 22.dp.toPx()
                    drawLine(ex.paperMargin, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 2f)
                }
            },
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Press feedback: springy scale-down + light haptic tick on every tappable element.
// ---------------------------------------------------------------------------

fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce",
    )
    val hf = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptic) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** Duolingo-style "3D" button: the face sinks onto its darker base when pressed. */
@Composable
fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = Color.Unspecified,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    // Default text colour follows the face colour (e.g. dark text on the light teal used in dark mode).
    val content = contentColor.takeOrElse { MaterialTheme.colorScheme.contentColorFor(color).takeOrElse { Color.White } }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth = 5.dp
    val sink by animateDpAsState(if (pressed && enabled) depth else 0.dp, spring(stiffness = Spring.StiffnessHigh), label = "sink")
    val face = if (enabled) color else MaterialTheme.colorScheme.surfaceVariant
    val base = if (enabled) lerp(color, Color.Black, 0.28f) else MaterialTheme.colorScheme.outline
    val fg = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant
    val hf = LocalHapticFeedback.current
    Box(
        modifier
            .height(height + depth)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
    ) {
        Box(
            Modifier.fillMaxWidth().height(height).offset(y = depth)
                .background(base, RoundedCornerShape(16.dp)),
        )
        Row(
            Modifier.fillMaxWidth().height(height).offset(y = sink)
                .background(face, RoundedCornerShape(16.dp))
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text.uppercase(), color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Secondary chunky button: paper face, outlined. */
@Composable
fun ChunkyOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth = 4.dp
    val sink by animateDpAsState(if (pressed && enabled) depth else 0.dp, spring(stiffness = Spring.StiffnessHigh), label = "sink")
    val edge = LocalExtras.current.cardEdge
    val hf = LocalHapticFeedback.current
    Box(
        modifier.height(52.dp + depth)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp).offset(y = depth).background(edge, RoundedCornerShape(16.dp)))
        Row(
            Modifier.fillMaxWidth().height(52.dp).offset(y = sink)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(2.dp, edge, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val c = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text.uppercase(), color = c, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Paper card with a soft "page edge" under it. Tappable when [onClick] is given. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    edge: Color = LocalExtras.current.cardEdge,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val m = if (onClick != null) modifier.bounceClick(pressedScale = 0.97f, onClick = onClick) else modifier
    val fg = MaterialTheme.colorScheme.contentColorFor(color).takeOrElse { MaterialTheme.colorScheme.onSurface }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) {
    Column(
        m.fillMaxWidth().padding(bottom = 4.dp)
            .drawBehind {
                // Page edge peeking out under the card.
                drawRoundRect(edge, topLeft = Offset(0f, 4.dp.toPx()), size = Size(size.width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
            }
            .clip(shape).background(color).border(BorderStroke(1.5.dp, edge), shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
    }
}

/** Small rounded label. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, textColor: Color = color) {
    Row(
        modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = textColor, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = textColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Selectable pill chip with bounce. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) color else MaterialTheme.colorScheme.surface, label = "chipbg")
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.bounceClick(onClick = onClick)
            .background(bg, RoundedCornerShape(50))
            .border(1.5.dp, if (selected) color else LocalExtras.current.cardEdge, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (selected) Color.White else color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------------------
// Rings, streak, avatars
// ---------------------------------------------------------------------------

@Composable
fun ProgressRing(
    progress: Float,
    size: Dp,
    stroke: Dp = 10.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = color.copy(alpha = 0.18f),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, -90f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * p, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun StreakFlame(days: Int, modifier: Modifier = Modifier, tint: Color = Palette.flame) {
    val t = rememberInfiniteTransition(label = "flame")
    val pulse by t.animateFloat(1f, if (days > 0) 1.12f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LocalFireDepartment, null, tint = if (days > 0) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(30.dp).scale(pulse))
        Spacer(Modifier.width(4.dp))
        Text("$days", style = MaterialTheme.typography.headlineSmall, color = if (days > 0) tint else tint.copy(alpha = 0.5f))
    }
}

@Composable
fun GuruAvatar(size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(
            Brush.linearGradient(listOf(LocalExtras.current.heroStart, Palette.marigold)), CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

private val avatarColors = listOf(Color(0xFF0B6E69), Color(0xFFE4572E), Color(0xFF3D8BD9), Color(0xFF8E5BA8), Color(0xFFB86E00), Color(0xFF3BA55C))

@Composable
fun LetterAvatar(name: String, size: Dp = 48.dp) {
    val c = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]
    Box(Modifier.size(size).background(c, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------------------
// Subject look: colour + icon per course (free-text courses get a stable colour).
// ---------------------------------------------------------------------------

data class SubjectLook(val color: Color, val icon: ImageVector)

fun subjectLook(course: String): SubjectLook {
    val c = course.lowercase()
    return when {
        "math" in c || "algebra" in c || "geometry" in c -> SubjectLook(Color(0xFF3D8BD9), Icons.Filled.Calculate)
        "physic" in c -> SubjectLook(Color(0xFF6C5CE7), Icons.Filled.Bolt)
        "chem" in c -> SubjectLook(Color(0xFF00A3A3), Icons.Filled.Science)
        "bio" in c -> SubjectLook(Color(0xFF3BA55C), Icons.Filled.Biotech)
        "computer" in c || "coding" in c || "program" in c -> SubjectLook(Color(0xFF8E5BA8), Icons.Filled.Computer)
        "social" in c || "civic" in c || "econom" in c -> SubjectLook(Color(0xFFB86E00), Icons.Filled.Public)
        "history" in c -> SubjectLook(Color(0xFFA0522D), Icons.Filled.HistoryEdu)
        "geograph" in c -> SubjectLook(Color(0xFF2E8B57), Icons.Filled.Terrain)
        "science" in c -> SubjectLook(Color(0xFF00A3A3), Icons.Filled.Science)
        "english" in c || "grammar" in c -> SubjectLook(Color(0xFFE4572E), Icons.AutoMirrored.Filled.MenuBook)
        "hindi" in c || "tamil" in c || "bengali" in c || "language" in c -> SubjectLook(Color(0xFFD6336C), Icons.Filled.Translate)
        else -> SubjectLook(avatarColors[(course.hashCode() and 0x7fffffff) % avatarColors.size], Icons.Filled.AutoStories)
    }
}

@Composable
fun SubjectBadge(course: String, size: Dp = 44.dp) {
    val look = subjectLook(course)
    Box(Modifier.size(size).background(look.color.copy(alpha = 0.15f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Icon(look.icon, null, tint = look.color, modifier = Modifier.size(size * 0.55f))
    }
}

// ---------------------------------------------------------------------------
// Motion helpers
// ---------------------------------------------------------------------------

/** Staggered fade + rise when an item first appears. */
fun Modifier.enterAnimation(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(8) * 55).toLong())
        a.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 28.dp.toPx()
    }
}

/** Horizontal shake whenever [trigger] changes (used for wrong answers). */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val x = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        for (d in listOf(14f, -12f, 9f, -6f, 3f, 0f)) x.animateTo(d, tween(55))
    }
    graphicsLayer { translationX = x.value.dp.toPx() }
}

/** "Pop" scale when [trigger] changes (used for correct answers). */
fun Modifier.pop(trigger: Int): Modifier = composed {
    val s = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        s.animateTo(1.06f, tween(120)); s.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    graphicsLayer { scaleX = s.value; scaleY = s.value }
}

private class Particle(val angle: Float, val speed: Float, val color: Color, val spin: Float, val w: Float)

/** Confetti burst from the top centre each time [trigger] increases. */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val colors = listOf(Palette.marigold, Color(0xFF3BA55C), Color(0xFF3D8BD9), Color(0xFFE4572E), Color(0xFF8E5BA8))
    val particles = remember(trigger) {
        val rnd = Random(trigger)
        List(70) {
            Particle(
                angle = (-PI / 2 + (rnd.nextFloat() - 0.5f) * PI * 1.1f).toFloat(),
                speed = 900f + rnd.nextFloat() * 900f,
                color = colors[rnd.nextInt(colors.size)],
                spin = rnd.nextFloat() * 720f,
                w = 8f + rnd.nextFloat() * 10f,
            )
        }
    }
    val t = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { t.animateTo(1f, tween(1500, easing = LinearEasing)) }
    if (t.value >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val time = t.value * 1.5f
        val origin = Offset(size.width / 2, size.height * 0.35f)
        for (p in particles) {
            val x = origin.x + cos(p.angle) * p.speed * time
            val y = origin.y + sin(p.angle) * p.speed * time + 1400f * time * time
            rotate(p.spin * time, Offset(x, y)) {
                drawRect(p.color.copy(alpha = (1f - t.value).coerceIn(0f, 1f)), Offset(x, y), Size(p.w, p.w * 0.5f))
            }
        }
    }
}

@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(0f, -6f, infiniteRepeatable(tween(380, delayMillis = i * 130), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).graphicsLayer { translationY = y.dp.toPx() }.background(color, CircleShape))
        }
    }
}

/**
 * Friendly waiting state while the on-device model works: rotating status lines, bouncing dots and a
 * small token counter, instead of raw streamed JSON.
 */
@Composable
fun ThinkingCard(messages: List<String>, progressText: String? = null, onStop: (() -> Unit)? = null, stopLabel: String = "") {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages) { while (true) { delay(2200); i = (i + 1) % messages.size } }
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val sweep by shimmer.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sweep")
    val base = MaterialTheme.colorScheme.primaryContainer
    SoftCard(color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GuruAvatar(36.dp)
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = messages[i % messages.size],
                transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                modifier = Modifier.weight(1f),
                label = "msg",
            ) { m -> Text(m, style = MaterialTheme.typography.titleMedium) }
            if (onStop != null) {
                Text(stopLabel, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.bounceClick(onClick = onStop).padding(8.dp))
            }
        }
        repeat(3) { row ->
            Box(
                Modifier.fillMaxWidth(if (row == 2) 0.6f else 1f).height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(Brush.linearGradient(
                        listOf(base.copy(alpha = 0.35f), base, base.copy(alpha = 0.35f)),
                        start = Offset(sweep * 600f, 0f), end = Offset(sweep * 600f + 400f, 0f),
                    )),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypingDots(MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (progressText != null) Text(progressText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Big icon in a soft circle, for empty states. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, color: Color = MaterialTheme.colorScheme.primary, action: @Composable () -> Unit = {}) {
    val t = rememberInfiniteTransition(label = "float")
    val dy by t.animateFloat(0f, -8f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(104.dp).graphicsLayer { translationY = dy.dp.toPx() }.background(color.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(52.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        action()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ---------------------------------------------------------------------------
// Background art (Settings → Appearance): school doodles drawn in code over the paper, theme-aware, very low
// contrast so text stays readable. No image files: they scale to any screen and recolour for dark mode.
// ---------------------------------------------------------------------------

private enum class DoodleKind { BOOK, PENCIL, ATOM, STAR, BULB, LEAF, RULER, GLYPH }

private data class Doodle(val kind: DoodleKind, val x: Float, val y: Float, val size: Float, val angle: Float, val glyph: String, val tint: Int)

private val glyphs = listOf("π", "√", "÷", "%", "+", "×", "=", "Δ", "அ", "অ", "∑", "∞")

@Composable
fun AppBackground(
    style: com.offlinetutor.data.AppearanceStore.Background,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    when (style) {
        com.offlinetutor.data.AppearanceStore.Background.PAPER -> PaperBackground(modifier, ruled = true, content = content)
        com.offlinetutor.data.AppearanceStore.Background.PLAIN -> {
            val ex = LocalExtras.current
            Box(modifier.fillMaxSize().drawBehind { drawRect(ex.paper) }, content = content)
        }
        com.offlinetutor.data.AppearanceStore.Background.DOODLES -> {
            val scheme = androidx.compose.material3.MaterialTheme.colorScheme
            val dark = scheme.background.luminance() < 0.4f
            val alpha = if (dark) 0.10f else 0.075f
            val tints = listOf(scheme.primary, scheme.tertiary, scheme.secondary).map { it.copy(alpha = alpha) }
            val doodles = remember {
                val rnd = Random(21)
                // Jittered grid so the art covers the screen evenly without clumps.
                buildList {
                    for (row in 0 until 9) for (col in 0 until 4) {
                        add(Doodle(DoodleKind.entries[rnd.nextInt(DoodleKind.entries.size)],
                            (col + 0.2f + rnd.nextFloat() * 0.6f) / 4f, (row + 0.2f + rnd.nextFloat() * 0.6f) / 9f,
                            26f + rnd.nextFloat() * 18f, rnd.nextFloat() * 50f - 25f, glyphs[rnd.nextInt(glyphs.size)], rnd.nextInt(3)))
                    }
                }
            }
            PaperBackground(modifier, ruled = false) {
                Canvas(Modifier.fillMaxSize()) { doodles.forEach { drawDoodle(it, tints[it.tint]) } }
                content()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDoodle(d: Doodle, color: Color) {
    val s = d.size.dp.toPx()
    val c = Offset(d.x * size.width, d.y * size.height)
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    rotate(d.angle, c) {
        when (d.kind) {
            DoodleKind.BOOK -> {
                drawRoundRect(color, Offset(c.x - s / 2, c.y - s / 3), androidx.compose.ui.geometry.Size(s, s * 0.66f),
                    androidx.compose.ui.geometry.CornerRadius(4f), style = stroke)
                drawLine(color, Offset(c.x, c.y - s / 3), Offset(c.x, c.y + s / 3), stroke.width)
                for (i in 1..2) {
                    drawLine(color, Offset(c.x - s * 0.4f, c.y - s / 3 + i * s * 0.2f), Offset(c.x - s * 0.1f, c.y - s / 3 + i * s * 0.2f), stroke.width)
                    drawLine(color, Offset(c.x + s * 0.1f, c.y - s / 3 + i * s * 0.2f), Offset(c.x + s * 0.4f, c.y - s / 3 + i * s * 0.2f), stroke.width)
                }
            }
            DoodleKind.PENCIL -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - s / 2, c.y - s * 0.09f); lineTo(c.x + s * 0.3f, c.y - s * 0.09f); lineTo(c.x + s / 2, c.y)
                    lineTo(c.x + s * 0.3f, c.y + s * 0.09f); lineTo(c.x - s / 2, c.y + s * 0.09f); close()
                }
                drawPath(p, color, style = stroke)
                drawLine(color, Offset(c.x + s * 0.3f, c.y - s * 0.09f), Offset(c.x + s * 0.3f, c.y + s * 0.09f), stroke.width)
            }
            DoodleKind.ATOM -> {
                for (a in listOf(0f, 60f, 120f)) rotate(a, c) {
                    drawOval(color, Offset(c.x - s / 2, c.y - s / 6), androidx.compose.ui.geometry.Size(s, s / 3), style = stroke)
                }
                drawCircle(color, s * 0.06f, c)
            }
            DoodleKind.STAR -> {
                val p = androidx.compose.ui.graphics.Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) s / 2 else s / 5
                    val t = Math.toRadians((i * 36 - 90).toDouble())
                    val pt = Offset(c.x + (r * kotlin.math.cos(t)).toFloat(), c.y + (r * kotlin.math.sin(t)).toFloat())
                    if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                }
                p.close(); drawPath(p, color, style = stroke)
            }
            DoodleKind.BULB -> {
                drawCircle(color, s * 0.3f, Offset(c.x, c.y - s * 0.1f), style = stroke)
                drawLine(color, Offset(c.x - s * 0.12f, c.y + s * 0.25f), Offset(c.x + s * 0.12f, c.y + s * 0.25f), stroke.width)
                drawLine(color, Offset(c.x - s * 0.09f, c.y + s * 0.34f), Offset(c.x + s * 0.09f, c.y + s * 0.34f), stroke.width)
            }
            DoodleKind.LEAF -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - s / 2, c.y); quadraticTo(c.x, c.y - s / 2, c.x + s / 2, c.y); quadraticTo(c.x, c.y + s / 2, c.x - s / 2, c.y)
                }
                drawPath(p, color, style = stroke)
                drawLine(color, Offset(c.x - s / 2, c.y), Offset(c.x + s * 0.35f, c.y), stroke.width)
            }
            DoodleKind.RULER -> {
                drawRect(color, Offset(c.x - s / 2, c.y - s * 0.12f), androidx.compose.ui.geometry.Size(s, s * 0.24f), style = stroke)
                for (i in 1..4) drawLine(color, Offset(c.x - s / 2 + i * s / 5, c.y - s * 0.12f), Offset(c.x - s / 2 + i * s / 5, c.y), stroke.width)
            }
            DoodleKind.GLYPH -> drawContext.canvas.nativeCanvas.drawText(d.glyph, c.x, c.y + s * 0.35f, android.graphics.Paint().apply {
                isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = s * 1.1f
                this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
            })
        }
    }
}
