package com.offlinetutor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinetutor.R

// ---------------------------------------------------------------------------
// "Notebook + chalkboard": warm paper, teal ink, marigold highlights.
// ---------------------------------------------------------------------------

private val Teal = Color(0xFF0B6E69)
private val TealDeep = Color(0xFF07524E)
private val Marigold = Color(0xFFF2A93B)
private val Ink = Color(0xFF2B2620)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDE8),
    onPrimaryContainer = Color(0xFF002522),
    secondary = Color(0xFFB86E00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3B8),
    onSecondaryContainer = Color(0xFF3A2300),
    tertiary = Color(0xFFE4572E),
    tertiaryContainer = Color(0xFFFFDBD0),
    onTertiaryContainer = Color(0xFF3B0A00),
    background = Color(0xFFFBF5E9),
    onBackground = Ink,
    surface = Color(0xFFFFFCF6),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFE6D6),
    onSurfaceVariant = Color(0xFF5E5547),
    surfaceContainer = Color(0xFFFFFCF6),
    surfaceContainerHigh = Color(0xFFF6EEE0),
    surfaceContainerHighest = Color(0xFFEFE6D6),
    surfaceContainerLow = Color(0xFFFFFDF9),
    outline = Color(0xFFD9CDB8),
    outlineVariant = Color(0xFFE8DECC),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6CE),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF0E4F4B),
    onPrimaryContainer = Color(0xFFCDEDE8),
    secondary = Color(0xFFFFC56B),
    onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF5E3D00),
    onSecondaryContainer = Color(0xFFFFE3B8),
    tertiary = Color(0xFFFF9D7E),
    tertiaryContainer = Color(0xFF6B2410),
    onTertiaryContainer = Color(0xFFFFDBD0),
    background = Color(0xFF1B2422),
    onBackground = Color(0xFFE9E4DA),
    surface = Color(0xFF243130),
    onSurface = Color(0xFFE9E4DA),
    surfaceVariant = Color(0xFF34413F),
    onSurfaceVariant = Color(0xFFC3CBC6),
    surfaceContainer = Color(0xFF243130),
    surfaceContainerHigh = Color(0xFF2C3A38),
    surfaceContainerHighest = Color(0xFF34413F),
    surfaceContainerLow = Color(0xFF212C2B),
    outline = Color(0xFF4B5A57),
    outlineVariant = Color(0xFF3A4745),
    error = Color(0xFFFF8A80),
)

// Baloo 2 (variable, OFL) for headings, bundled so it works offline. Body text uses the system
// font, which also covers Tamil and Bengali glyphs via Android's Noto fallback.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun baloo(weight: Int) = Font(
    R.font.baloo2,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Baloo = FontFamily(baloo(500), baloo(600), baloo(700), baloo(800))

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp),
        headlineLarge = t.headlineLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = t.headlineSmall.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = t.titleMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = t.titleSmall.copy(fontFamily = Baloo, fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontFamily = Baloo, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Colours outside the Material roles: feedback, levels and the paper texture. */
@Immutable
data class Extras(
    val paper: Color,
    val paperLine: Color,
    val paperMargin: Color,
    val speck: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val cardEdge: Color,
)

private val LightExtras = Extras(
    paper = Color(0xFFFBF5E9),
    paperLine = Color(0x143D6FB6),
    paperMargin = Color(0x26E4572E),
    speck = Color(0x0D4A3A20),
    heroStart = Teal,
    heroEnd = TealDeep,
    cardEdge = Color(0xFFE6DBC7),
)

private val DarkExtras = Extras(
    paper = Color(0xFF1B2422),
    paperLine = Color(0x0FFFFFFF),
    paperMargin = Color(0x00000000),
    speck = Color(0x14FFFFFF),
    heroStart = Color(0xFF0E5C57),
    heroEnd = Color(0xFF093E3B),
    cardEdge = Color(0xFF15201F),
)

val LocalExtras = staticCompositionLocalOf { LightExtras }

object Palette {
    val correct = Color(0xFF3BA55C)
    val correctBg = Color(0xFFE3F5E8)
    val wrong = Color(0xFFE5484D)
    val wrongBg = Color(0xFFFDE6E6)
    val easy = Color(0xFF3BA55C)
    val medium = Color(0xFFF2A93B)
    val hard = Color(0xFFE5484D)
    val flame = Color(0xFFFF8A1F)
    val marigold = Marigold
}

@Composable
/** [darkOverride]: null = follow the phone, true / false = the in-app Dark / Light choice (Settings → Appearance). */
fun OfflineTutorTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
            // Screens use transparent Scaffolds over the paper, so set the default text/icon colour
            // explicitly; otherwise it falls back to black, which is unreadable in dark mode.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
