package com.clarity.life.ui.quality

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The suggestion cards drawn for real (Max plan Phase 7): nothing is saved without a tap, each button does only
 * what it says, every button is big enough to tap, and the cards still work with the phone's largest text.
 * Runs on the build machine with Robolectric; the plain Application keeps the app's own start-up (database,
 * keystore, background work) out of a screen test.
 */
@RunWith(RobolectricTestRunner::class)
// Real text drawing, so text sizes are measured as on a phone (the default mode only estimates them).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class CardScreensTest {
    @get:Rule val compose = createComposeRule()

    private val s = CardSamples()

    /** Taps the [index]th button labelled [label]; a label that is not a button (a chip) is never tapped. */
    private fun click(label: String, index: Int = 0) {
        compose.onAllNodes(hasText(label) and hasClickAction())[index].performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun nothingIsSavedWithoutATap() {
        compose.setContent { s.Gallery() }
        compose.waitForIdle()
        assertTrue("cards acted by themselves: ${s.taps}", s.taps.isEmpty())
    }

    @Test
    fun eachButtonDoesOnlyWhatItSays() {
        compose.setContent { s.Gallery() }
        click("Set it")
        click("Keep it")
        click("Start it")
        click("Switch")
        click("Save plan")
        assertEquals(listOf("setPromise 1", "keepNote 3", "startFocus 4", "switchMode 5", "savePlan 6"), s.taps)
    }

    @Test
    fun notNowOnlySaysNotNow() {
        compose.setContent { s.Gallery() }
        // In gallery order: the two promises, the note, the focus, the plan and the blocked change.
        repeat(6) { click("Not now", it) }
        click("Stay here")
        assertEquals(listOf("notNow 1", "notNow 2", "notNow 3", "notNow 4", "notNow 6", "notNow 7", "notNow 5"), s.taps)
    }

    @Test
    fun pickingATimeSavesNothingUntilSetIt() {
        compose.setContent { s.Gallery(listOf(s.unclearPromise)) }
        click("Pick a time")
        compose.onNodeWithText("Nothing is saved until you tap Set it.").assertIsDisplayed()
        assertTrue("opening the time picker saved something: ${s.taps}", s.taps.isEmpty())
    }

    @Test
    fun aSavedCardOpensWhatWasSavedAndADismissedOneIsQuiet() {
        compose.setContent { s.Gallery(listOf(s.saved, s.dismissed)) }
        compose.onNodeWithText("Kept in What I know.").assertIsDisplayed()
        compose.onNodeWithText("Not saved. The coach will not offer it again in this chat.").assertIsDisplayed()
        assertTrue(s.taps.isEmpty())
    }

    @Test
    fun everyButtonIsBigEnoughToTap() {
        compose.setContent { s.Gallery() }
        checkTapSizes()
    }

    @Test
    fun theLargestTextStillFitsAndEveryButtonCanBeReached() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { s.Gallery() }
        checkTapSizes()
        val buttons = compose.onAllNodes(hasClickAction())
        repeat(buttons.fetchSemanticsNodes().size) { buttons[it].performScrollTo().assertIsDisplayed() }
        checkNoTextIsCut()
    }

    /** Android's guideline: anything tappable is at least 48 by 48 dp, the size of a fingertip. */
    private fun checkTapSizes() {
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("no buttons found", nodes.isNotEmpty())
        val min = with(compose.density) { 48.dp.roundToPx() }
        // The whole button, including the space Material adds around a small one to make it easy to tap.
        val small = nodes.filter { it.layoutInfo.width < min || it.layoutInfo.height < min }
            .map { "${it.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"}: ${it.layoutInfo.width}x${it.layoutInfo.height}px" }
        assertTrue("buttons smaller than 48 dp ($min px): $small", small.isEmpty())
    }

    /** A line ends in the middle of a word ("Re" / "minder"): the text had too little room to wrap at a space. */
    private fun brokenWord(t: TextLayoutResult): Boolean {
        val text = t.layoutInput.text.text
        return (0 until t.lineCount - 1).any { line ->
            val end = t.getLineEnd(line)
            end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
        }
    }

    /** No text is clipped or cut short, and no word is split across lines, with the largest text. */
    private fun checkNoTextIsCut() {
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("no text found", texts.isNotEmpty())
        val cut = texts.flatMap { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            // Width is not checked: a short text in a wide space always reads as "narrower than allowed".
            // What matters is that no line is hidden at the bottom and no word is split.
            layouts.filter { it.didOverflowHeight || it.multiParagraph.didExceedMaxLines || brokenWord(it) }.map {
                "\"${it.layoutInput.text}\" (box ${it.size.width}x${it.size.height}px, text height " +
                    "${it.multiParagraph.height}px, ${it.lineCount} lines, word broken ${brokenWord(it)})"
            }
        }
        assertTrue("text cut short:\n" + cut.joinToString("\n"), cut.isEmpty())
    }
}
