package com.clarity.life.ui.quality

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clarity.life.ui.theme.BigFact
import com.clarity.life.ui.theme.BoldCard
import com.clarity.life.ui.theme.CalmCard
import com.clarity.life.ui.theme.ClarityTheme
import com.clarity.life.ui.theme.DangerButton
import com.clarity.life.ui.theme.Headline
import com.clarity.life.ui.theme.Kicker
import com.clarity.life.ui.theme.OutlineCard
import com.clarity.life.ui.theme.Pill
import com.clarity.life.ui.theme.PrimaryButton
import com.clarity.life.ui.theme.Quiet
import com.clarity.life.ui.theme.SecondaryButton
import com.clarity.life.ui.theme.TextAction
import com.clarity.life.ui.theme.clarity
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the cards and the Bold building blocks (Max plan Phase 7). The approved pictures are committed in
 * app/src/test/screenshots/; CI draws them again on every push and fails when one changes, so a change to the
 * look is always on purpose. After an approved change, the "Record screenshots" workflow takes new ones.
 * The owner compares them with the mockups in docs/mockups/redesign/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val s = CardSamples()

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test fun cardsPromises() { compose.setContent { s.Gallery(listOf(s.promise, s.unclearPromise)) }; snap("cards_promises") }

    @Test fun cardsNoteFocusMode() { compose.setContent { s.Gallery(listOf(s.note, s.focus, s.mode)) }; snap("cards_note_focus_mode") }

    @Test fun cardsPlan() { compose.setContent { s.Gallery(listOf(s.plan, s.blocked)) }; snap("cards_plan") }

    @Test fun cardsAfterATap() { compose.setContent { s.Gallery(listOf(s.saved, s.dismissed)) }; snap("cards_after_a_tap") }

    @Test
    fun cardsLargestText() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { s.Gallery(listOf(s.promise, s.note)) }
        snap("cards_largest_text")
    }

    @Test
    fun boldBlocks() {
        compose.setContent {
            ClarityTheme {
                Column(
                    Modifier.fillMaxSize().background(clarity.ground).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Kicker("This week")
                    Headline("Pass the exam")
                    BigFact("3 of 4")
                    Quiet("A quiet line, the way the app says something went wrong.")
                    Pill("On pace")
                    Pill("Behind", warn = true)
                    BoldCard { Quiet("A surface card") }
                    OutlineCard { Quiet("An outlined card") }
                    CalmCard { Quiet("A calm offer") }
                    PrimaryButton("Primary") {}
                    SecondaryButton("Secondary") {}
                    DangerButton("Delete") {}
                    TextAction("Text action") {}
                }
            }
        }
        snap("bold_blocks")
    }
}
