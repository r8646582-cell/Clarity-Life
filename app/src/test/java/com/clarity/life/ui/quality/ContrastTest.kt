package com.clarity.life.ui.quality

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.clarity.life.ui.theme.Tokens
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every pair of text and background colour the app uses is easy to read (Max plan Phase 7): at least 4.5 to 1,
 * the WCAG AA rule for normal text. Plain JVM, no screen needed.
 */
class ContrastTest {

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val grounds = mapOf(
        "Ground" to Tokens.Ground, "Surface" to Tokens.Surface, "Raised" to Tokens.Raised, "TabBar" to Tokens.TabBar,
        "YellowTint" to Tokens.YellowTint, "CoralTint" to Tokens.CoralTint, "GreenTint" to Tokens.GreenTint,
    )
    private val inks = mapOf(
        "TextOn" to Tokens.TextOn, "Muted" to Tokens.Muted, "Yellow" to Tokens.Yellow, "Coral" to Tokens.Coral,
        "Green" to Tokens.Green, "Danger" to Tokens.Danger,
    )

    @Test
    fun everyTextColourIsReadableOnEveryDarkBackground() {
        val bad = inks.flatMap { (i, ink) ->
            grounds.mapNotNull { (g, ground) -> ratio(ink, ground).let { r -> if (r < 4.5) "$i on $g: %.2f".format(r) else null } }
        }
        assertTrue("too faint to read: $bad", bad.isEmpty())
    }

    @Test
    fun buttonsAndPaperAreReadable() {
        val pairs = mapOf(
            "OnYellow on Yellow" to ratio(Tokens.OnYellow, Tokens.Yellow),
            "Ground on Coral" to ratio(Tokens.Ground, Tokens.Coral),
            "PaperInk on Paper" to ratio(Tokens.PaperInk, Tokens.Paper),
            "PaperMuted on Paper" to ratio(Tokens.PaperMuted, Tokens.Paper),
        )
        val bad = pairs.filterValues { it < 4.5 }
        assertTrue("too faint to read: $bad", bad.isEmpty())
    }
}
