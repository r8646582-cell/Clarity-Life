package com.clarity.life.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live text of the reply being streamed right now, so the screen can show it letter by letter while the
 * database copy is only written about once a second (that copy is what survives leaving the app).
 *
 * It holds one reply at a time and nothing else: no history, no cache, nothing to go stale.
 */
@Singleton
class ReplyBus @Inject constructor() {

    data class Live(
        val messageId: Long,
        val text: String = "",
        val thinking: Boolean = false,
        val note: String? = null,
    )

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live

    fun begin(messageId: Long) {
        _live.value = Live(messageId = messageId)
    }

    fun append(messageId: Long, text: String) {
        val current = _live.value ?: return
        if (current.messageId != messageId) return
        _live.value = current.copy(text = current.text + text, thinking = false)
    }

    fun thinking(messageId: Long) {
        val current = _live.value ?: return
        if (current.messageId == messageId && current.text.isEmpty()) {
            _live.value = current.copy(thinking = true)
        }
    }

    fun note(messageId: Long, note: String) {
        val current = _live.value ?: return
        if (current.messageId == messageId) _live.value = current.copy(note = note)
    }

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the last reply did not arrive, in one plain line, until he sends again. */
    val failure: StateFlow<String?> = _failure

    fun fail(line: String?) {
        _failure.value = line
    }

    fun textOf(messageId: Long): String = _live.value?.takeIf { it.messageId == messageId }?.text.orEmpty()

    fun end(messageId: Long) {
        if (_live.value?.messageId == messageId) _live.value = null
    }
}
