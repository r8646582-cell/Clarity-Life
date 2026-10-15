package com.clarity.life.ui.plan

/**
 * What waits for his answer on Plan (the owner's decision, 14 Oct 2026): today's steps and due promises live on
 * Plan only, not on Talk home, and a coral dot on the Plan tab and on the section's chip says something is
 * still open. The dot goes as soon as he answers. A rest day asks nothing. Written by code from the same
 * lists the sections show, so a dot always matches what is on the screen behind it.
 */
object Waiting {

    /** A step scheduled today with no answer yet. */
    fun today(plan: PlanViewModel.State): Boolean =
        plan.ready && !plan.restToday && plan.todayRows.any { it.answer == null }

    /** A promise due on his day or earlier and not answered (what Plan, Promises shows under Needs an answer). */
    fun promises(promises: PromisesViewModel.State): Boolean = promises.due.isNotEmpty()

    /** The same, from the light view the tab bar reads (PlanViewModel.Home): no check-in history needed. */
    fun today(home: PlanViewModel.Home): Boolean = home.ready && home.todayUnanswered

    /** The Plan tab's dot: anything above, or an unread letter or review. */
    fun planTab(plan: PlanViewModel.State, promises: PromisesViewModel.State, unreadReviews: Boolean): Boolean =
        unreadReviews || today(plan) || promises(promises)

    /** The Plan tab's dot as the tab bar reads it, from the light view. */
    fun planTab(home: PlanViewModel.Home, promises: PromisesViewModel.State, unreadReviews: Boolean): Boolean =
        unreadReviews || today(home) || promises(promises)
}
