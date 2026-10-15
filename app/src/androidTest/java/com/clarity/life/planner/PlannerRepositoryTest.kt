package com.clarity.life.planner

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarity.life.core.CardParser
import com.clarity.life.core.KnownIds
import com.clarity.life.core.PlanApply
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.PlanDates
import com.clarity.life.core.StrictJson
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The planner commands on a real (in-memory) database: one row per day, history kept, plans versioned. */
@RunWith(AndroidJUnit4::class)
class PlannerRepositoryTest {

    private lateinit var db: ClarityDatabase
    private lateinit var planner: PlannerRepository
    private val now = 1_760_000_000_000L
    private val today = LocalDate.parse("2026-10-10")

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClarityDatabase::class.java).build()
        planner = PlannerRepository(db, SettingsRepository(db.settings(), SecretStore(context)))
    }

    @After
    fun close() = db.close()

    private suspend fun lectures(goal: Long): Long =
        planner.addTarget(goal, null, "Lectures", "lectures", 1.0, 2.0, "Sat-Thu", today, null)

    /**
     * Improvement report S3: the remembered tracker always equals a fresh read once a write has landed. Room
     * tells the cache about a write a moment after it commits, so this waits up to two seconds for that.
     */
    @Test
    fun theRememberedTrackerFollowsEveryWrite() = runBlocking {
        suspend fun same() {
            val deadline = System.currentTimeMillis() + 2_000
            while (planner.tracker(today) != planner.readTracker(today) && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
            assertEquals(planner.readTracker(today), planner.tracker(today))
        }
        same()
        val g = planner.createGoal("Clear the lectures", "studies", "custom", LocalDate.parse("2026-11-20"), now)
        same()
        val t = lectures(g)
        same()
        planner.checkIn(t, today, today, "target", 2.0, null, null, now)
        same()
        assertEquals(1, planner.tracker(today).targets.single().checkins.size)
        planner.clearCheckIn(t, today)
        same()
        assertTrue(planner.tracker(today).targets.single().checkins.isEmpty())
    }

    @Test
    fun oneAnswerPerTargetPerDayAndUndoIsNoAnswer() = runBlocking {
        val g = planner.createGoal("Clear the lectures", "studies", "custom", LocalDate.parse("2026-11-20"), now)
        val t = lectures(g)
        planner.checkIn(t, today, today, "not", 0.0, "tired", null, now)
        planner.checkIn(t, today, today, "target", 2.0, null, null, now + 1)
        val rows = db.planner().checkinsOf(t)
        assertEquals(1, rows.size)
        assertEquals("target", rows[0].level)
        planner.clearCheckIn(t, today)
        assertNull(db.planner().checkin(t, today.toString()))
    }

    @Test
    fun aCheckInIsRefusedForTomorrowOrWithAReasonOnADoneDay() = runBlocking {
        val t = lectures(planner.createGoal("Lectures", "studies", "custom", null, now))
        for (attempt in listOf<suspend () -> Unit>(
            { planner.checkIn(t, today.plusDays(1), today, "target", 2.0, null, null, now) },
            { planner.checkIn(t, today, today, "target", 2.0, "tired", null, now) },
            { planner.checkIn(t, today, today, "not", 0.0, "made up", null, now) },
            { planner.checkIn(t, today, today, "maybe", 0.0, null, null, now) },
        )) {
            try {
                attempt()
                fail("should have been refused")
            } catch (e: IllegalArgumentException) {
                // refused, as it should be
            }
        }
        assertTrue(db.planner().checkinsOf(t).isEmpty())
    }

    @Test
    fun aGoalChangeKeepsARevisionAndATargetWithHistoryCannotBeDeleted() = runBlocking {
        val g = planner.createGoal("Lectures", "studies", "custom", null, now)
        planner.reviseGoal(g, "All lectures", LocalDate.parse("2026-11-20"), "exam date known", now + 1)
        assertEquals(2, db.planner().revisions(g).size)
        val t = lectures(g)
        planner.checkIn(t, today, today, "target", 2.0, null, null, now)
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM target WHERE id = $t")
            fail("a target with check-ins must not be deletable")
        } catch (e: Exception) {
            // RESTRICT in the schema
        }
        planner.setTargetStatus(t, "ended", today)
        assertEquals(today.toString(), db.planner().target(t)!!.endDay)
        assertEquals(1, db.planner().checkinsOf(t).size)
    }

    /** Max plan Phase 6: every change he taps is kept with its day, so what the plan asked for stays known. */
    @Test
    fun aTargetsChangesAreKeptWithTheirDay() = runBlocking {
        val later = 1_792_065_600_000L // 15 Oct 2026, 12:00 UTC: five days after the target started
        val t = lectures(planner.createGoal("Lectures", "studies", "custom", null, now))
        assertEquals(listOf("2026-10-10" to 2.0), db.planner().targetVersions(listOf(t)).map { it.fromDay to it.target })
        val old = planner.oldTargets().first { it.id == t }
        planner.applyTargetChange(PlanApply.TargetStep.Change(old, target = 3.0), later)
        val day = planner.today(later).toString()
        assertEquals(listOf("2026-10-10" to 2.0, day to 3.0), db.planner().targetVersions(listOf(t)).map { it.fromDay to it.target })
        // A change of its end alone is not a new version; pausing it is.
        planner.applyTargetChange(PlanApply.TargetStep.Change(planner.oldTargets().first { it.id == t }, end = LocalDate.parse("2026-12-01")), later)
        assertEquals(2, db.planner().targetVersions(listOf(t)).size)
        planner.setTargetStatus(t, "paused", LocalDate.parse(day))
        assertEquals("paused", db.planner().targetVersions(listOf(t)).last().status)
        assertEquals(2, db.planner().targetVersions(listOf(t)).size)  // same day: the pause replaces that day's row
        val tracker = planner.tracker(LocalDate.parse(day))
        assertTrue("a paused target is not live", tracker.targets.none { it.id == t })
    }

    @Test
    fun savingASyllabusPlanTwiceMakesVersionsAndReusesTheTarget() = runBlocking {
        val g = planner.createGoal("Clear the lectures", "studies", "custom", LocalDate.parse("2026-11-20"), now)
        val tracker0 = planner.tracker(today)
        val known = PlanBlocks.known(tracker0, today)
        val json = """{"type":"plan","title":"Clear the lectures","goal":"G$g","rationale":"Raise the pace","targets":[{"name":"Lectures","unit":"lectures","min":1,"target":2,"schedule":"Sat-Thu","end":"20 Nov"}],"backlog":[{"name":"Lectures","total":40,"unit":"lectures","deadline":"20 Nov"}],"review_date":"${known.reviewOptions.first()}"}"""
        suspend fun save(j: String): Long {
            val card = CardParser.validate(StrictJson.parse(j), PlanBlocks.known(planner.tracker(today), today))
            val days = (PlanDates.checkCard(card, today) as PlanDates.Checked.Ok).days
            val steps = PlanApply.plan(card, days, planner.activeGoalIds(), planner.oldTargets(), planner.oldBacklogs())
            return planner.applyPlan(steps, null, now)
        }
        save(json)
        save(json.replace("\"target\":2", "\"target\":3"))
        val plans = db.planner().plans(g)
        assertEquals(listOf(2, 1), plans.map { it.version })
        assertEquals(listOf("active", "replaced"), plans.map { it.status })
        val targets = db.planner().activeTargets()
        assertEquals(1, targets.size)
        assertEquals(3.0, targets[0].target, 0.0)
        assertEquals(plans[0].id, targets[0].planId)
        val backlogs = db.planner().activeBacklogs()
        assertEquals(1, backlogs.size)
        assertEquals(backlogs[0].id, targets[0].backlogId)
        // and PLAN_STATE now shows it
        val text = PlanBlocks.planState(planner.tracker(today), today).items.joinToString("\n") { it.text }
        assertTrue(text, text.contains("Backlog B${backlogs[0].id} \"Lectures\": total 40, done 0"))
        assertTrue(text, text.contains("Target T${targets[0].id} \"Lectures\" (G$g): minimum 1, target 3, Sat-Thu."))
    }

    @Test
    fun aPlanCardWithAnUnknownGoalIsDroppedByTheParser() {
        try {
            CardParser.validate(
                StrictJson.parse("""{"type":"plan","title":"x","goal":"G9","rationale":"r","targets":[{"name":"a","target":1,"schedule":"daily"}],"review_date":"17 Oct"}"""),
                KnownIds(reviewOptions = setOf("17 Oct")),
            )
            fail()
        } catch (e: Exception) {
            // unknown id
        }
    }
}
