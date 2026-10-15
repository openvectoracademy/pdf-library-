package com.pdflib

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
class Pal(
    val dark: Boolean, val bg: Color, val surface: Color, val ink: Color, val soft: Color,
    val line: Color, val accent: Color, val chip: Color, val hi: Color
)

// Paper & ink: warm off-white, near-black ink, one vermilion accent, a highlighter yellow for stars/bookmarks.
val LightPal = Pal(false, Color(0xFFF6F3EC), Color(0xFFFFFDF8), Color(0xFF1B1A17), Color(0xFF6E6A5F), Color(0xFFE4DFD3), Color(0xFFC8452D), Color(0xFFECE7DA), Color(0xFFE2A91F))
val DarkPal = Pal(true, Color(0xFF14130F), Color(0xFF1D1C17), Color(0xFFF0ECE2), Color(0xFF9D988B), Color(0xFF2D2B24), Color(0xFFE8664B), Color(0xFF29271F), Color(0xFFE9B949))
val LocalPal = staticCompositionLocalOf { LightPal }

val FolderTones = listOf(
    Color(0xFFC8452D), Color(0xFFC58B1B), Color(0xFF5B7F3B),
    Color(0xFF2F7F79), Color(0xFF4B5FA8), Color(0xFF8A4B7A)
)

@Composable
fun PdfLibTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.ink,
        surface = p.surface, onSurface = p.ink, surfaceVariant = p.chip, onSurfaceVariant = p.soft,
        outline = p.line, outlineVariant = p.line
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.ink,
        surface = p.surface, onSurface = p.ink, surfaceVariant = p.chip, onSurfaceVariant = p.soft,
        outline = p.line, outlineVariant = p.line
    )
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
