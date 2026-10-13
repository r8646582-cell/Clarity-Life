package com.clarity.life.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Doorways between the tabs. "Plan with coach", "Talk about this letter" and "Talk about it" ask Talk to open a
 * new conversation (spec section 2.2); the letter card on Talk asks Plan to open a letter. Each request is taken
 * once and cleared. Nothing is written here.
 */
@Singleton
class TalkRequests @Inject constructor() {

    /** A new conversation of [chatType], opened from a letter or a review when [openedFrom] is set. */
    data class Start(
        val chatType: String,
        val openedFrom: String? = null,
        val openedFromId: Long? = null,
        /** A conversation inside this focus (Talk about this, on a Focus page). */
        val focusId: Long? = null,
        /** A message typed for him into the box ("Add the ramp" on a goal); he sends it, or not. */
        val draft: String? = null,
    )

    /** What Plan should show: a section, and a letter or review to open in it. */
    data class Show(val section: String, val letterId: Long? = null, val reviewId: Long? = null)

    private val pending = MutableStateFlow<Start?>(null)
    private val plan = MutableStateFlow<Show?>(null)

    val requests: StateFlow<Start?> get() = pending
    val planRequests: StateFlow<Show?> get() = plan

    fun startConversation(
        chatType: String,
        openedFrom: String? = null,
        openedFromId: Long? = null,
        focusId: Long? = null,
        draft: String? = null,
    ) {
        pending.value = Start(chatType, openedFrom, openedFromId, focusId, draft)
    }

    /** Talk took the request. Returns it, or null when there was none. */
    fun take(): Start? = pending.getAndUpdate { null }

    fun showInPlan(show: Show) {
        plan.value = show
    }

    fun takePlan(): Show? = plan.getAndUpdate { null }
}
