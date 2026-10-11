package com.clarity.life.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.ai.Usage
import com.clarity.life.chat.ChatRepository
import com.clarity.life.data.DatabaseModule
import com.clarity.life.prompts.PromptStore
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The part of a chat that only runs when the provider really answers: build the request from the bundled
 * prompts, then store a finished reply with a card and the token counts, on the real encrypted database.
 * The fake-key walk in tools/emulator_smoke.sh never gets this far because the provider refuses the key.
 */
@RunWith(AndroidJUnit4::class)
class ChatRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aFinishedReplyIsBuiltAndStored() = runBlocking {
        val secrets = SecretStore(context)
        val db = DatabaseModule.database(context, secrets)
        try {
            val http = OkHttpClient()
            val chat = ChatRepository(
                db = db,
                settings = SettingsRepository(db.settings(), secrets),
                prompts = PromptStore(context, db.promptOverrides()),
                mainClient = OpenAiCompatibleClient(http),
                anthropicClient = AnthropicClient(http),
                search = com.clarity.life.memory.SearchIndex(db, com.clarity.life.memory.Embedder(context)),
                planner = com.clarity.life.planner.PlannerRepository(db, SettingsRepository(db.settings(), secrets)),
            )
            val now = System.currentTimeMillis()
            val id = chat.startConversation("free", null, now)
            chat.addHisMessage(id, "I keep putting off my chemistry revision.", now)
            val coachId = chat.openCoachReply(id, now, "test-model")

            val packet = chat.buildPacket(id, coachId, now)
            assertTrue(packet.request.system.isNotBlank())
            assertTrue(packet.request.messages.isNotEmpty())
            // His message goes once, in the final turn; the history holds only what came before it.
            assertEquals(1, packet.request.messages.count { it.content.contains("I keep putting off my chemistry revision.") })
            assertTrue(packet.request.messages.last().content.endsWith("I keep putting off my chemistry revision."))

            val raw = "That sounds heavy. What is the first small thing?\n" +
                "[[suggest: {\"type\":\"promise\",\"text\":\"Study for 20 minutes\",\"when\":\"tomorrow 17:00\"," +
                "\"remind\":true,\"why\":\"A first piece of evidence\"}]]"
            val outcome = chat.closeCoachReply(
                conversationId = id,
                messageId = coachId,
                raw = raw,
                chatType = "free",
                known = packet.known,
                finishReason = "stop",
                usage = Usage(tokensIn = 1200, tokensOut = 80, tokensCached = 900),
                now = System.currentTimeMillis(),
            )
            assertEquals(1, outcome.cardsOffered)
            assertEquals("complete", chat.messages.get(coachId)?.status)
            assertEquals(1, chat.cards.recentOfConversation(id, 9).size)

            // A second message in the same conversation reuses the stored prefix.
            chat.addHisMessage(id, "Tomorrow after dinner.", now + 1000)
            chat.buildPacket(id, "Tomorrow after dinner.", now + 2000)
            chat.forgetConversation(id)
        } finally {
            db.close()
        }
    }
}
