package com.clarity.life.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.data.DatabaseModule
import com.clarity.life.prompts.PromptStore
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reply service's last resort (bug scan 14 Oct 2026, full scan, finding 1): a reply that ended in a way
 * nothing else handled must never stay `streaming`, or Talk shows "thinking" for ever and holds Send.
 */
@RunWith(AndroidJUnit4::class)
class ReplySettleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val failure = AiFailure("unexpected", "the reply could not be finished", retryable = true)

    @Test
    fun aStuckReplyIsSettledAndAFinishedOneIsLeftAlone() = runBlocking {
        val secrets = SecretStore(context)
        val db = DatabaseModule.database(context, secrets)
        try {
            val http = OkHttpClient()
            val settings = SettingsRepository(db.settings(), secrets)
            val chat = ChatRepository(
                db = db,
                settings = settings,
                prompts = PromptStore(context, db.promptOverrides()),
                mainClient = OpenAiCompatibleClient(http),
                anthropicClient = AnthropicClient(http),
                search = com.clarity.life.memory.SearchIndex(db, com.clarity.life.memory.TestEmbedder.shared),
                planner = com.clarity.life.planner.PlannerRepository(db, settings),
            )
            val now = System.currentTimeMillis()
            val id = chat.startConversation("free", null, now)

            // Nothing arrived: failed, with Try again.
            chat.addHisMessage(id, "First message.", now)
            val empty = chat.openCoachReply(id, now, "test-model")
            assertEquals("failed", chat.settleIfStreaming(empty, "", failure, now))

            // Part of the reply arrived: interrupted, and the words are kept.
            chat.addHisMessage(id, "Second message.", now + 1)
            val half = chat.openCoachReply(id, now + 1, "test-model")
            assertEquals("interrupted", chat.settleIfStreaming(half, "Half a reply", failure, now + 1))
            assertEquals("Half a reply", chat.messages.get(half)?.content)

            // A reply already stored as complete stays complete.
            chat.addHisMessage(id, "Third message.", now + 2)
            val done = chat.openCoachReply(id, now + 2, "test-model")
            chat.messages.setStatus(done, "complete")
            assertEquals("complete", chat.settleIfStreaming(done, "", failure, now + 2))

            chat.forgetConversation(id)
        } finally {
            db.close()
        }
    }
}
