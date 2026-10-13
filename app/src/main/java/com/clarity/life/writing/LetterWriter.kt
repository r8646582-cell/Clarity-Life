package com.clarity.life.writing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clarity.life.MainActivity
import com.clarity.life.R
import com.clarity.life.ai.AiFailure
import com.clarity.life.core.LetterInput
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.ReviewFacts
import com.clarity.life.core.ReviewTimes
import com.clarity.life.core.TextJobCheck
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.LetterRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The weekly letter (spec section 8): on by default, written on Sunday from 19:30 for the week that is ending,
 * from the week's conversations, check-ins and review facts (prompts/letter_weekly.md). It is published only if
 * every quotation is his, word for word, and every number is in its inputs; after two failed checks it is not
 * published and Plan, Reviews says "This week's letter is delayed" with Try again. One letter per week (the key),
 * and a letter he deleted is never written again. Nothing here can change any of his plans, and no card is made.
 */
@Singleton
class LetterWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val call: BackgroundCall,
    private val jobs: JobClaims,
    private val planner: PlannerRepository,
    private val reviews: ReviewWriter,
) {
    private val dao get() = db.writing()

    sealed interface Outcome {
        object Written : Outcome
        object NothingToDo : Outcome
        data class Retry(val code: String) : Outcome
        data class Failed(val code: String) : Outcome
    }

    /** The Monday of the week whose letter is due now, or null when letters are off or nothing is tracked. */
    suspend fun dueWeek(now: Long): LocalDate? {
        if (!settings.getBool(SettingsRepository.Keys.WEEKLY_LETTER_ON, com.clarity.life.settings.DefaultSettings.WEEKLY_LETTER_ON)) return null
        return ReviewTimes.latestWeek(reviews.localNow(now), ReviewTimes.LETTER_AT)
    }

    /** Writes the letter for the week starting [monday] if it is worth writing and not written yet. */
    suspend fun write(monday: LocalDate, now: Long, retry: Boolean = false): Outcome {
        val key = ReviewTimes.letterKey(monday)
        if (dao.letterByKey(key) != null) return Outcome.NothingToDo
        val sunday = monday.plusDays(6)
        val zone = ZoneId.of(settings.timeZone())
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = sunday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val conversations = dao.conversationsBetween(from, to).mapNotNull { id ->
            val c = db.conversations().get(id) ?: return@mapNotNull null
            if (c.offTheRecord == 1) return@mapNotNull null
            val msgs = db.messages().ofConversation(id)
                .filter { it.createdAt in from until to && it.content.isNotBlank() && (it.role == "user" || it.status != "failed") }
            LetterInput.Conversation(
                id = id,
                day = TimeFacts.shortDay(TimeFacts.local(java.time.Instant.ofEpochMilli(c.startedAt), settings.timeZone()).toLocalDate()),
                title = c.title,
                summary = c.summary,
                reflected = c.reflectedUpToMessageId > 0,
                messages = msgs.map { it.role to it.content },
            )
        }.sortedBy { it.id }
        val answeredDays = db.planner().checkinsBetween(monday.toString(), sunday.toString())
            .filter { it.level != "rest" }.map { it.dayKey }.toSet().size
        if (!LetterInput.worthWriting(conversations, answeredDays)) {
            // Settled for good only once his Sunday has ended (the day runs to the boundary hour on Monday);
            // until then an evening check-in or conversation may still make the week worth a letter.
            if (planner.today(now).isAfter(sunday)) jobs.skip(key, TYPE, now)
            return Outcome.NothingToDo
        }
        val endpoint = settings.letterEndpoint() ?: return Outcome.NothingToDo
        val job = when (val c = jobs.claim(key, TYPE, now, retry, MAX_CHECKS)) {
            is JobClaims.Claim.Settled -> return if (c.status == "failed") Outcome.Failed("failed") else Outcome.NothingToDo
            is JobClaims.Claim.Run -> c.job
        }
        try {
            val review = reviews.ensure("week", monday, sunday, now)
            val reviewFacts = review?.let { ReviewFacts.fromJson(it.factsJson)?.text } ?: "(none)"
            val today = planner.today(now)
            val m = db.memory()
            val values = mapOf(
                "USER_NAME" to settings.promptName(),
                "PERIOD_START" to TimeFacts.shortDay(monday),
                "PERIOD_END" to TimeFacts.shortDay(sunday),
                "PROFILE" to m.profile().joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "(none)" },
                "PEOPLE" to m.people().joinToString("\n") { "${it.name} (${it.relation}): ${it.notes}" }.ifBlank { "(none)" },
                "NOTES" to m.activeNotes().joinToString("\n") { "${it.kind}: ${it.text}" }.ifBlank { "(none)" },
                "REVIEW_FACTS" to reviewFacts,
                "PLAN_STATE" to PlanBlocks.planState(planner.tracker(today), today).items.joinToString("\n") { it.text },
                "FOCUS_SUMMARIES" to dao.activeFocuses().joinToString("\n") { "${it.name}: ${it.summary.ifBlank { it.why }}" }.ifBlank { "(none)" },
                "PREVIOUS_LETTER" to (dao.letterBefore(monday.toString())?.let { "${it.title}\n${it.body}" } ?: "(none)"),
                "RECENT_IDEAS" to m.recentItems("idea", 20).joinToString("\n") { it.text }.ifBlank { "(none)" },
                "TRANSCRIPTS" to LetterInput.transcripts(conversations),
            )
            val his = conversations.flatMap { c -> c.messages.filter { it.first == "user" }.map { it.second } } +
                db.planner().checkinsBetween(monday.toString(), sunday.toString()).mapNotNull { it.note }
            val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT), values), today.toString().take(7))
            val checked = TextJobCheck.check("letter", raw, values.values.toList(), his)
            if (!checked.ok) return failed(job, "check_failed", now)
            val inserted = dao.insertLetter(
                LetterRow(
                    periodStart = monday.toString(),
                    periodEnd = sunday.toString(),
                    title = checked.title,
                    body = checked.body,
                    readyAt = now,
                    key = key,
                ),
            )
            jobs.succeed(job, now)
            // Only a letter really written now rings the Sunday alert (a second run's insert is ignored).
            if (inserted > 0) alert()
            return Outcome.Written
        } catch (e: CancellationException) {
            jobs.release(job)
            throw e
        } catch (e: BackgroundCall.CutOff) {
            return failed(job, "cut_off", now)
        } catch (e: AiFailure) {
            return failed(job, e.code, now)
        } catch (e: Throwable) {
            jobs.fail(job, AREA, "error_" + e.javaClass.simpleName, now, MAX_CHECKS)
            return Outcome.Failed("error")
        }
    }

    private suspend fun failed(job: com.clarity.life.data.entity.JobRow, code: String, now: Long): Outcome =
        if (jobs.fail(job, AREA, code, now, MAX_CHECKS)) Outcome.Retry(code) else Outcome.Failed(code)

    /** True when this week's letter gave up after its checks: the list shows "delayed" with Try again. */
    suspend fun delayed(monday: LocalDate): Boolean {
        if (dao.letterByKey(ReviewTimes.letterKey(monday)) != null) return false
        val job = jobs.status(ReviewTimes.letterKey(monday)) ?: return false
        return job.status == "failed" && job.attempts >= MAX_CHECKS
    }

    /** The optional silent Sunday alert: one quiet notification, no sound, nothing on the lock screen. */
    private suspend fun alert() {
        if (!settings.getBool(SettingsRepository.Keys.SUNDAY_ALERT_ON, com.clarity.life.settings.DefaultSettings.SUNDAY_ALERT_ON)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Created every time on purpose: Android then updates the name and description on a phone that already
        // has the channel (his own choices for it, like turning it off, are kept).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly letter", NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
                setSound(null, null)
            },
        )
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle("Your weekly letter is ready")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            db.diagnostics().log(ErrorLogRow(at = System.currentTimeMillis(), area = AREA, code = "notifications_denied", detail = ""))
        }
    }

    suspend fun markRead(id: Long, now: Long) = dao.markLetterRead(id, now)

    /** His deletion is final: the text goes, the key stays, so it is never written again. */
    suspend fun delete(id: Long, now: Long) = dao.deleteLetter(id, now)

    fun watchAll() = dao.watchLetters()

    fun watch(id: Long) = dao.watchLetter(id)

    companion object {
        const val TYPE = "letter"
        const val AREA = "letter"
        const val PROMPT = "letter_weekly.md"
        const val CHANNEL = "weekly_letter"

        /** Shown in Android's notification settings; it names where the switch really is in Clarity. */
        const val CHANNEL_DESCRIPTION =
            "One silent line when your weekly letter is ready. Turn it off in Clarity: Settings, Letters and your day, Sunday alert."
        const val NOTIFICATION_ID = 7_001

        /** A letter that fails its checks twice is not published (spec section 8). */
        const val MAX_CHECKS = 2
    }
}
