package com.clarity.life.ui.plan

import com.clarity.life.data.entity.CheckinRow
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.data.entity.TargetRow
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dot on the Plan tab and on its Today and Promises chips (the owner's decision, 14 Oct 2026). */
class WaitingTest {

    private val today = LocalDate.of(2026, 10, 14)
    private val lectures = TargetRow(id = 1, name = "CAF lectures", unit = "lectures", target = 2.0, schedule = "daily", startDay = "2026-10-14")
    private val answered = CheckinRow(targetId = 1, dayKey = "2026-10-14", level = "target", amount = 2.0, enteredAt = 0)

    private fun plan(answer: CheckinRow?, rest: Boolean = false) = PlanViewModel.State(
        ready = true,
        today = today,
        restToday = rest,
        todayRows = listOf(PlanViewModel.TodayRow(lectures, answer)),
    )

    private val duePromise = PromisesViewModel.State(
        due = listOf(
            PromisesViewModel.Item(
                PromiseRow(id = 7, text = "Watch the first CAF lecture at 8am", dueAt = 0, dueText = "", createdAt = 0),
                kicker = "",
                time = "",
            ),
        ),
    )

    @Test
    fun anUnansweredStepShowsTheDotAndAnsweringItTakesItAway() {
        assertTrue(Waiting.today(plan(answer = null)))
        assertTrue(Waiting.planTab(plan(answer = null), PromisesViewModel.State(), unreadReviews = false))
        assertFalse(Waiting.today(plan(answer = answered)))
        assertFalse(Waiting.planTab(plan(answer = answered), PromisesViewModel.State(), unreadReviews = false))
    }

    @Test
    fun aDuePromiseShowsTheDotUntilItIsAnswered() {
        assertTrue(Waiting.promises(duePromise))
        assertTrue(Waiting.planTab(plan(answer = answered), duePromise, unreadReviews = false))
        // Answered: it leaves the due list, so the dot goes.
        assertFalse(Waiting.promises(PromisesViewModel.State()))
    }

    @Test
    fun aRestDayAsksNothing() {
        assertFalse(Waiting.today(plan(answer = null, rest = true)))
    }

    @Test
    fun beforeThePlanIsReadThereIsNoDot() {
        assertFalse(Waiting.today(PlanViewModel.State()))
        assertFalse(Waiting.planTab(PlanViewModel.State(), PromisesViewModel.State(), unreadReviews = false))
    }

    @Test
    fun anUnreadLetterStillShowsTheDot() {
        assertTrue(Waiting.planTab(PlanViewModel.State(), PromisesViewModel.State(), unreadReviews = true))
    }
}
