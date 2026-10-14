package com.clarity.life.lifetime

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.ai.AiFailure
import com.clarity.life.ai.AnthropicClient
import com.clarity.life.ai.OpenAiCompatibleClient
import com.clarity.life.chat.ChatRepository
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StartupRepair
import com.clarity.life.memory.SearchIndex
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lifetime timing harness (docs/MAX-PLAN.md, Phase 0 step 4): ten years of heavy use ([TenYears]) in a
 * separate encrypted database, then the app's own code timed on it: start-up database work, the plan
 * tracker, search (first and later), building a request (new conversation and next message), rebuilding the
 * search index, memory held, and the file size.
 *
 * It records numbers and never fails on them: the targets in the plan are for a mid-range phone, and the CI
 * emulator is not one. It runs only when asked (instrumentation argument clarity_timing=1); CI runs it in its
 * own step and prints the lines tagged ClarityTiming.
 */
@RunWith(AndroidJUnit4::class)
class LifetimeTimingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun line(text: String) {
        Log.i(TAG, text)
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val out = block()
        line("%-44s %8.1f ms".format(label, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6))
        return out
    }

    private fun open(secrets: SecretStore): ClarityDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, ClarityDatabase::class.java, NAME)
            .openHelperFactory(SupportOpenHelperFactory(secrets.databaseKey(), null, true))
            .addMigrations(*ClarityDatabase.MIGRATIONS)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @Test
    fun tenYearsOfUse() = runBlocking {
        assumeTrue("runs only with clarity_timing=1", InstrumentationRegistry.getArguments().getString("clarity_timing") == "1")
        context.deleteDatabase(NAME)
        val secrets = SecretStore(context)
        val now = Instant.parse("2026-10-14T05:00:00Z")
        val nowMs = now.toEpochMilli()
        try {
            line("ten-year timing run, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}")
            var db = open(secrets)
            val counts = timed("generate ten years (not app work)") { TenYears().fill(db.openHelper.writableDatabase, now) }
            line("data: $counts")
            db.close()

            // Start-up: open the encrypted file, then the database work the start-up sweep does.
            db = timed("open the database (first query)") {
                open(secrets).also { it.openHelper.writableDatabase.query("SELECT count(*) FROM message").use { c -> c.moveToFirst() } }
            }
            val settings = SettingsRepository(db.settings(), secrets)
            val embedder = com.clarity.life.memory.TestEmbedder.shared
            val search = SearchIndex(db, embedder)
            val planner = PlannerRepository(db, settings)
            val chat = ChatRepository(
                db = db,
                settings = settings,
                prompts = PromptStore(context, db.promptOverrides()),
                mainClient = OpenAiCompatibleClient(OkHttpClient()),
                anthropicClient = AnthropicClient(OkHttpClient()),
                search = search,
                planner = planner,
            )
            timed("start-up repair sweep") { StartupRepair(db.repair(), db.diagnostics()).run(nowMs) }
            timed("start-up: search index check") { search.rebuildIfEmpty() }
            timed("drawer: every conversation's row") { db.conversations().watchRecent(ChatRepository.DRAWER_LIMIT).first() }

            val today = LocalDate.parse("2026-10-14")
            timed("plan tracker (10 years of check-ins)") { planner.tracker(today) }
            timed("plan tracker again") { planner.tracker(today) }

            // Search: the model's first load, then the first search (all vectors read), then later ones.
            timed("meaning model: first sentence (load)") { embedder.embed("warm up the model") }
            val conversation = chat.startConversation("free", null, nowMs)
            val questions = listOf(
                "I keep avoiding the tax lectures because of power cuts",
                "how did the gym go with my knee last year",
                "Ammi was worried about money again and the wedding",
                "same thing as last time with my phone in bed",
                "which promises did I break in August",
            )
            val first = timed("search: first (reads every vector)") { search.relevantPast(conversation, questions[0], "UTC", 0, nowMs) }
            assertTrue("ten years of messages must give search results", first.items.isNotEmpty())
            val warm = questions.drop(1).map { q ->
                val t0 = SystemClock.elapsedRealtimeNanos()
                search.relevantPast(conversation, q, "UTC", 0, nowMs)
                (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            }.sorted()
            line("%-44s %8.1f ms (median of %d, worst %.1f)".format("search: later", warm[warm.size / 2], warm.size, warm.last()))
            val heap = Runtime.getRuntime()
            line("memory: %.0f MB in use of %.0f MB allowed, after search".format(
                (heap.totalMemory() - heap.freeMemory()) / 1e6, heap.maxMemory() / 1e6))

            // A request: the first message of a new conversation builds the frozen prefix; the next reuses it.
            chat.addHisMessage(conversation, questions[0], nowMs)
            val reply1 = chat.openCoachReply(conversation, nowMs, "timing")
            timed("request: new conversation (prefix built)") { chat.buildPacket(conversation, reply1, nowMs) }
            chat.settleIfStreaming(reply1, "A reply.", AiFailure("timing", "timing run"), nowMs)
            chat.addHisMessage(conversation, questions[1], nowMs + 60_000)
            val reply2 = chat.openCoachReply(conversation, nowMs + 60_000, "timing")
            timed("request: next message (prefix reused)") { chat.buildPacket(conversation, reply2, nowMs + 60_000) }
            chat.settleIfStreaming(reply2, "A reply.", AiFailure("timing", "timing run"), nowMs + 60_000)

            timed("Settings: rebuild search index") { search.rebuild() }

            val file = context.getDatabasePath(NAME)
            val wal = java.io.File(file.path + "-wal")
            line("database file: %.1f MB (+ %.1f MB write-ahead log)".format(file.length() / 1e6, wal.length() / 1e6))
            db.close()
        } finally {
            context.deleteDatabase(NAME)
        }
    }

    private companion object {
        const val TAG = "ClarityTiming"
        const val NAME = "lifetime-timing.db"
    }
}
