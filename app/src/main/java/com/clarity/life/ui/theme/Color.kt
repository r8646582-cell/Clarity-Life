package com.clarity.life.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The 0.1.6 redesign tokens (docs/mockups/redesign/README.md, spec section 2). These are the only colours in
 * the app; screens take them from [ClarityColors], never as literals.
 */
object Tokens {
    val Ground = Color(0xFF0F0E13)
    val Surface = Color(0xFF1A1820)
    val Raised = Color(0xFF24212C)
    val TextOn = Color(0xFFF5F3F8)
    val Muted = Color(0xFFA9A4B6)
    val Yellow = Color(0xFFFFD23F)
    val OnYellow = Color(0xFF17140A)
    val Coral = Color(0xFFFF7A66)
    val Green = Color(0xFF8FD6A0)
    val Danger = Color(0xFFFF8A7C)
    val Hairline = Color(0xFF2D2A36)
    val ControlOutline = Color(0xFF3A3645)
    val TabBar = Color(0xFF0B0A0E)

    /** The quiet tints behind a calm offer (yellow), a suggestion card (coral) and a saved tick (green). */
    val YellowTint = Color(0xFF1D1B16)
    val YellowEdge = Color(0xFF4A3F1E)
    val CoralTint = Color(0xFF1E1719)
    val CoralEdge = Color(0xFF5A2E2A)
    val GreenTint = Color(0xFF1E2A22)

    /** The paper card the letter and his own words sit on. */
    val Paper = Color(0xFFF3EEE2)
    val PaperInk = Color(0xFF1A1712)
    val PaperMuted = Color(0xFF6B6150)
}
