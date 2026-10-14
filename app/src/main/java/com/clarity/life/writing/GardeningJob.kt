package com.clarity.life.writing

import com.clarity.life.ai.AiFailure
import com.clarity.life.core.GardeningApplier
import com.clarity.life.core.GardeningContext
import com.clarity.life.core.GardeningInput
import com.clarity.life.core.NoteFacts
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.memory.MemoryRepository
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.prompts.PromptStore
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Monthly memory gardening (spec section 7, prompts/gardening.md): the background model reads every active
 * note, the profile, the people and about three months of session summaries, and returns merges, rewrites
 * and retirements. [GardeningApplier] checks them and [MemoryRepository.applyGardening] applies what
 * survived in one transaction. It writes memory only: never a card, a goal, a plan, a target or a promise.
 *
 * Once per calendar month (job key "gardening:YYYY-MM"), and once more in a week when a kind of note has
 * reached its cap, because a full kind silently blocks every new note of that kind. Nothing to tidy means
 * no call.
 */
@Singleton
class GardeningJob @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val prompts: PromptStore,
    private val call: BackgroundCall,
    private val jobs: JobClaims,
    private val memory: MemoryRepository,
    private val planner: PlannerRepository,
    private val noteVectors: com.clarity.life.memory.NoteVectors,
) {
    sealed interface Outcome {
        data class Done(val applied: Int, val dropped: Int) : Outcome
        object NothingToDo : Outcome
        data class Retry(val code: String) : Outcome
        data class Failed(val code: String) : Outcome
    }

    /** The month's run, or the extra one when a kind is full. Safe to call on every catch-up. */
    suspend fun runDue(now: Long): Outcome {
        val today = planner.today(now)
        val month = run(GardeningInput.monthKey(today), today, now)
        if (month is Outcome.Retry) return month
        val full = db.memory().activeNotes().groupingBy { it.kind }.eachCount()
            .any { it.value >= MemoryRepository.NOTE_CAP_PER_KIND }
        // Not twice in one go: when this month's run has just tidied, the extra run waits for a later catch-up.
        return if (full && month is Outcome.NothingToDo) run(GardeningInput.capKey(today), today, now) else month
    }

    private suspend fun run(key: String, today: LocalDate, now: Long): Outcome {
        jobs.status(key)?.let { if (it.status == "succeeded" || it.status == "skipped") return Outcome.NothingToDo }
        val m = db.memory()
        val evidence = m.allEvidence().groupingBy { it.noteId }.eachCount()
        val notes = m.activeNotes().map { n ->
            GardeningInput.Note(
                id = n.id,
                kind = n.kind,
                confidence = n.confidence,
                timesSeen = n.timesSeen,
                firstSeen = n.firstSeen,
                lastSeen = n.lastSeen,
                evidence = evidence[n.id] ?: 0,
                edited = n.editedByUser == 1,
                text = n.text,
            )
        }
        val people = m.people().map { GardeningInput.Person(it.id, it.name, it.relation, it.notes) }
        val profile = m.profile().map { GardeningInput.Profile(it.key, it.value, it.confirmed == 1) }
        if (!GardeningInput.worthRunning(notes, people, profile)) {
            jobs.skip(key, TYPE, now)
            return Outcome.NothingToDo
        }
        val endpoint = settings.backgroundEndpoint(json = true) ?: return Outcome.NothingToDo
        val job = when (val c = jobs.claim(key, TYPE, now)) {
            is JobClaims.Claim.Settled -> return if (c.status == "failed") Outcome.Failed("failed") else Outcome.NothingToDo
            is JobClaims.Claim.Run -> c.job
        }
        try {
            val zone = ZoneId.of(settings.timeZone())
            val from = today.minusMonths(GardeningInput.SUMMARY_MONTHS).atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = db.writing().summarisedBetween(from, now + 1).map { c ->
                GardeningInput.Summary(
                    day = TimeFacts.local(Instant.ofEpochMilli(c.startedAt), zone.id).toLocalDate().toString(),
                    title = c.title,
                    text = c.summary,
                )
            }
            val values = mapOf(
                "USER_NAME" to settings.promptName(),
                // With the notes, the groups the code finds close (Max plan Phase 3 step 5): suggestions only.
                "NOTES" to GardeningInput.notes(notes, today) + GardeningInput.closeGroups(
                    GardeningInput.clusters(
                        m.activeNotes().associate { n ->
                            n.id to NoteFacts(n.kind, n.confidence, n.status, n.editedByUser == 1, n.text, n.timesSeen)
                        },
                        noteVectors::of,
                    ),
                ),
                "PROFILE" to GardeningInput.profile(profile),
                "PEOPLE" to GardeningInput.people(people),
                "SUMMARIES" to GardeningInput.summaries(sessions),
            )
            val raw = call.text(endpoint, prompts.fill(prompts.text(PROMPT), values), today.toString().take(7))
            // Checked against every note (retired and deleted too), so an id that is not active is refused.
            val ctx = GardeningContext(
                notes = m.allNotes().associate { n ->
                    n.id to NoteFacts(n.kind, n.confidence, n.status, n.editedByUser == 1, n.text, n.timesSeen)
                },
                people = people.map { it.id }.toSet(),
                profile = profile.associate { it.key to it.confirmed },
                deletedNotes = m.deletedNoteTexts(),
                vectors = noteVectors::of,
            )
            val result = GardeningApplier.apply(raw, ctx)
            if (!result.ok) return failed(job, "json_rejected", now)
            val applied = memory.applyGardening(job, result.ops, now) ?: return Outcome.NothingToDo
            val dropped = result.dropped.size + applied.skipped
            if (dropped > 0) {
                db.diagnostics().log(ErrorLogRow(at = now, area = AREA, code = "items_dropped", detail = "n=$dropped"))
            }
            return Outcome.Done(applied.applied, dropped)
        } catch (e: CancellationException) {
            jobs.release(job)
            throw e
        } catch (e: BackgroundCall.CutOff) {
            return failed(job, "cut_off", now)
        } catch (e: AiFailure) {
            return failed(job, e.code, now)
        } catch (e: Throwable) {
            jobs.fail(job, AREA, "error_" + e.javaClass.simpleName, now)
            return Outcome.Failed("error")
        }
    }

    private suspend fun failed(job: com.clarity.life.data.entity.JobRow, code: String, now: Long): Outcome =
        if (jobs.fail(job, AREA, code, now)) Outcome.Retry(code) else Outcome.Failed(code)

    companion object {
        const val TYPE = "gardening"
        const val AREA = "gardening"
        const val PROMPT = "gardening.md"
    }
}
