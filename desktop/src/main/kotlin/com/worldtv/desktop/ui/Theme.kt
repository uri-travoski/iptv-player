package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The three looks of the Android app (App Settings > Theme), same colours. */
@Immutable
data class WtColors(
    val id: String,
    val label: String,
    val background: Brush,
    val bar: Color,
    val surface: Color,
    val surfaceActive: Color,
    val slot: Color,
    val stroke: Color,
    val divider: Color,
    val accent: Color,
    val pressed: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val scrim: Color,
    /** Home slider: the backdrop fades into this behind the tiles. */
    val heroFade: Color = Color(0xFF2A0A12),
    val good: Color = Color(0xFF4CD964),
    val error: Color = Color(0xFFFF8A80),
)

object Themes {
    const val CLASSIC = "classic"
    const val GLASS = "glass"
    const val SHOWTIME = "showtime"

    /** A linear gradient at [angle] degrees, as Android's gradient drawables draw it. */
    private fun gradient(angle: Float, colors: List<Color>): Brush {
        val r = Math.toRadians(angle.toDouble())
        val dx = kotlin.math.cos(r).toFloat()
        val dy = -kotlin.math.sin(r).toFloat()
        return Brush.linearGradient(
            colors,
            start = Offset(if (dx < 0) 2000f else 0f, if (dy < 0) 1200f else 0f),
            end = Offset(if (dx < 0) 0f else 2000f, if (dy < 0) 0f else 1200f),
        )
    }

    val classic = WtColors(
        id = CLASSIC,
        label = "Classic",
        background = Brush.verticalGradient(listOf(Color(0xFF2A0A12), Color(0xFF2A0A12))),
        bar = Color(0xFF2A0A12),
        surface = Color(0xFF3F111C),
        surfaceActive = Color(0xFF5E1A2A),
        slot = Color(0x803F111C),
        stroke = Color(0x00000000),
        divider = Color(0xFF57202D),
        accent = Color(0xFFB0243F),
        pressed = Color(0xFFE8607A),
        textPrimary = Color(0xFFF7EEF0),
        textSecondary = Color(0xFFC9A9B0),
        scrim = Color(0xD91A060B),
    )

    val glass = WtColors(
        id = GLASS,
        label = "Glass",
        background = gradient(315f, listOf(Color(0xFF0B1D3A), Color(0xFF1B2F66), Color(0xFF3A1F5E))),
        bar = Color(0x00000000),
        surface = Color(0x26FFFFFF),
        surfaceActive = Color(0x40FFFFFF),
        slot = Color(0x1AFFFFFF),
        stroke = Color(0x40FFFFFF),
        divider = Color(0x33FFFFFF),
        accent = Color(0xFF2F9BE0),
        pressed = Color(0xFF7CD3FF),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xCCDCE6F5),
        scrim = Color(0xD90B1630),
        heroFade = Color(0xFF0E1A3A),
    )

    val showtime = WtColors(
        id = SHOWTIME,
        label = "Showtime",
        background = gradient(300f, listOf(Color(0xFF0E0A33), Color(0xFF24105A), Color(0xFF4A0E5C))),
        bar = Color(0x00000000),
        surface = Color(0xFF2B1B66),
        surfaceActive = Color(0xFF40298F),
        slot = Color(0x992B1B66),
        stroke = Color(0x00000000),
        divider = Color(0xFF3D2C85),
        accent = Color(0xFFFF7A00),
        pressed = Color(0xFFFFB347),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFC9C0F2),
        scrim = Color(0xD9120830),
        heroFade = Color(0xFF150B3C),
    )

    val ALL = listOf(classic, glass, showtime)

    fun of(id: String) = ALL.firstOrNull { it.id == id } ?: classic
}

val LocalWt = staticCompositionLocalOf { Themes.classic }

@Composable
fun WorldTheme(colors: WtColors, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        secondary = colors.accent,
        background = colors.surface,
        surface = colors.surface.copy(alpha = 1f).let { if (colors.surface.alpha < 1f) Color(0xFF1C2546) else it },
        onSurface = colors.textPrimary,
        onBackground = colors.textPrimary,
        surfaceVariant = colors.surfaceActive,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.divider,
        surfaceContainerHigh = if (colors.surface.alpha < 1f) Color(0xFF1C2546) else colors.surface,
        surfaceContainer = if (colors.surface.alpha < 1f) Color(0xFF1C2546) else colors.surface,
        error = colors.error,
    )
    val base = Typography()
    val typography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalWt provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** The theme's background (a colour, or a gradient for Glass and Showtime) behind a screen. */
@Composable
fun ThemedBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(LocalWt.current.background), content = content)
}
