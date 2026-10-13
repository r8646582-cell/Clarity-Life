package com.clarity.life.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The 0.1.6 palette, exactly as the redesign mockups use it. One dark theme only; there is no light mode in v1. */
data class ClarityColors(
    val ground: Color = Tokens.Ground,
    val surface: Color = Tokens.Surface,
    val raised: Color = Tokens.Raised,
    val text: Color = Tokens.TextOn,
    val muted: Color = Tokens.Muted,
    val yellow: Color = Tokens.Yellow,
    val onYellow: Color = Tokens.OnYellow,
    val coral: Color = Tokens.Coral,
    val green: Color = Tokens.Green,
    val danger: Color = Tokens.Danger,
    val hairline: Color = Tokens.Hairline,
    val controlOutline: Color = Tokens.ControlOutline,
    val tabBar: Color = Tokens.TabBar,
    val yellowTint: Color = Tokens.YellowTint,
    val yellowEdge: Color = Tokens.YellowEdge,
    val coralTint: Color = Tokens.CoralTint,
    val coralEdge: Color = Tokens.CoralEdge,
    val greenTint: Color = Tokens.GreenTint,
    val paper: Color = Tokens.Paper,
    val paperInk: Color = Tokens.PaperInk,
    val paperMuted: Color = Tokens.PaperMuted,
)

val LocalClarityColors = staticCompositionLocalOf { ClarityColors() }

private val Scheme = darkColorScheme(
    primary = Tokens.Yellow,
    onPrimary = Tokens.OnYellow,
    secondary = Tokens.Coral,
    onSecondary = Tokens.Ground,
    background = Tokens.Ground,
    onBackground = Tokens.TextOn,
    surface = Tokens.Surface,
    onSurface = Tokens.TextOn,
    surfaceVariant = Tokens.Raised,
    onSurfaceVariant = Tokens.Muted,
    outline = Tokens.ControlOutline,
    outlineVariant = Tokens.Hairline,
    error = Tokens.Danger,
    onError = Tokens.Ground,
)

@Composable
fun ClarityTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalClarityColors provides ClarityColors()) {
        MaterialTheme(colorScheme = Scheme, typography = ClarityTypography, content = content)
    }
}

/** Shorthand so screens read `clarity.coral` instead of reaching for a literal. */
val clarity: ClarityColors
    @Composable get() = LocalClarityColors.current
