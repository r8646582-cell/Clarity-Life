package com.clarity.life.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.clarity.life.data.entity.BacklogRow
import com.clarity.life.data.entity.CheckinRow
import com.clarity.life.data.entity.GoalRevisionRow
import com.clarity.life.data.entity.GoalRow
import com.clarity.life.data.entity.PlanRow
import com.clarity.life.data.entity.RestDayRow
import com.clarity.life.data.entity.TargetRow
import com.clarity.life.data.entity.TargetVersionRow
import kotlinx.coroutines.flow.Flow

/**
 * Goals, plans, targets, backlogs, rest days and check-ins. Only [com.clarity.life.planner.PlannerRepository]
 * and [com.clarity.life.chat.CardSaver] write through this, and every write follows a tap of his.
 * Nothing here deletes a goal, a target or a check-in's history: those are ended or archived.
 */
@Dao
interface PlannerDao {
    // ---------- goals ----------

    @Insert
    suspend fun insertGoal(row: GoalRow): Long

    @Update
    suspend fun updateGoal(row: GoalRow)

    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun goal(id: Long): GoalRow?

    @Query("SELECT * FROM goal WHERE status = 'active' ORDER BY id")
    suspend fun activeGoals(): List<GoalRow>

    @Query("SELECT * FROM goal ORDER BY CASE status WHEN 'active' THEN 0 ELSE 1 END, id DESC")
    fun watchGoals(): Flow<List<GoalRow>>

    @Insert
    suspend fun insertRevision(row: GoalRevisionRow): Long

    @Query("SELECT * FROM goal_revision WHERE goal_id = :goalId ORDER BY id")
    suspend fun revisions(goalId: Long): List<GoalRevisionRow>

    // ---------- plans ----------

    @Insert
    suspend fun insertPlan(row: PlanRow): Long

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun plan(id: Long): PlanRow?

    @Query("SELECT COALESCE(MAX(version), 0) FROM plan WHERE goal_id = :goalId")
    suspend fun lastPlanVersion(goalId: Long): Int

    @Query("UPDATE plan SET status = 'replaced' WHERE goal_id = :goalId AND status = 'active'")
    suspend fun replaceActivePlans(goalId: Long)

    @Query("SELECT * FROM plan WHERE goal_id = :goalId ORDER BY version DESC")
    suspend fun plans(goalId: Long): List<PlanRow>

    @Query("SELECT * FROM plan ORDER BY goal_id, version DESC")
    fun watchPlans(): Flow<List<PlanRow>>

    // ---------- targets ----------

    @Insert
    suspend fun insertTarget(row: TargetRow): Long

    @Update
    suspend fun updateTarget(row: TargetRow)

    @Query("SELECT * FROM target WHERE id = :id")
    suspend fun target(id: Long): TargetRow?

    @Query("SELECT * FROM target WHERE status = 'active' ORDER BY id")
    suspend fun activeTargets(): List<TargetRow>

    /** Every target that ran at some point in [first]..[last], whatever its status now (for reviews). */
    @Query(
        "SELECT * FROM target WHERE start_day <= :last AND (end_day IS NULL OR end_day >= :first) ORDER BY id",
    )
    suspend fun targetsDuring(first: String, last: String): List<TargetRow>

    @Query("SELECT MIN(start_day) FROM target")
    suspend fun firstTargetDay(): String?

    @Query("SELECT * FROM plan WHERE created_at >= :since ORDER BY goal_id, version")
    suspend fun plansSince(since: Long): List<PlanRow>

    @Query("SELECT * FROM target ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END, id")
    fun watchTargets(): Flow<List<TargetRow>>

    // ---------- target history (schema 8) ----------

    /** A change he tapped, from a day on; a second change the same day replaces the first (unique day). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTargetVersion(row: TargetVersionRow): Long

    @Query("SELECT COUNT(*) FROM target_version WHERE target_id = :targetId")
    suspend fun targetVersionCount(targetId: Long): Int

    /** The versions of these targets, oldest first per target. */
    @Query("SELECT * FROM target_version WHERE target_id IN (:targetIds) ORDER BY target_id, from_day")
    suspend fun targetVersions(targetIds: List<Long>): List<TargetVersionRow>

    @Query("SELECT * FROM target_version ORDER BY target_id, from_day")
    fun watchTargetVersions(): Flow<List<TargetVersionRow>>

    // ---------- backlogs ----------

    @Insert
    suspend fun insertBacklog(row: BacklogRow): Long

    @Update
    suspend fun updateBacklog(row: BacklogRow)

    @Query("SELECT * FROM backlog WHERE id = :id")
    suspend fun backlog(id: Long): BacklogRow?

    /** Every target ever pointed at these backlogs, paused and ended ones too (their work still counts). */
    @Query("SELECT * FROM target WHERE backlog_id IN (:backlogIds) ORDER BY id")
    suspend fun targetsOfBacklogs(backlogIds: List<Long>): List<TargetRow>

    @Query("SELECT * FROM backlog WHERE status = 'active' ORDER BY id")
    suspend fun activeBacklogs(): List<BacklogRow>

    /** A backlog from before schema 3 gets its creation day once; a day already there is never changed. */
    @Query("UPDATE backlog SET start_day = :day WHERE id = :id AND start_day IS NULL")
    suspend fun fillBacklogStartDay(id: Long, day: String)

    @Query("SELECT * FROM backlog ORDER BY CASE status WHEN 'active' THEN 0 ELSE 1 END, id")
    fun watchBacklogs(): Flow<List<BacklogRow>>

    // ---------- rest days ----------

    @Insert
    suspend fun insertRestDay(row: RestDayRow)

    @Query("DELETE FROM rest_day WHERE day_key = :dayKey")
    suspend fun deleteRestDay(dayKey: String)

    @Query("SELECT * FROM rest_day ORDER BY day_key")
    suspend fun restDays(): List<RestDayRow>

    @Query("SELECT * FROM rest_day ORDER BY day_key")
    fun watchRestDays(): Flow<List<RestDayRow>>

    // ---------- check-ins ----------

    @Insert
    suspend fun insertCheckin(row: CheckinRow): Long

    @Update
    suspend fun updateCheckin(row: CheckinRow)

    @Query("SELECT * FROM checkin WHERE target_id = :targetId AND day_key = :dayKey")
    suspend fun checkin(targetId: Long, dayKey: String): CheckinRow?

    @Query("DELETE FROM checkin WHERE target_id = :targetId AND day_key = :dayKey")
    suspend fun deleteCheckin(targetId: Long, dayKey: String)

    @Query("SELECT * FROM checkin WHERE target_id = :targetId ORDER BY day_key")
    suspend fun checkinsOf(targetId: Long): List<CheckinRow>

    @Query("SELECT * FROM checkin WHERE target_id IN (:targetIds) ORDER BY day_key")
    suspend fun checkinsOf(targetIds: List<Long>): List<CheckinRow>

    @Query("SELECT * FROM checkin WHERE day_key >= :fromDay AND day_key <= :toDay ORDER BY day_key")
    suspend fun checkinsBetween(fromDay: String, toDay: String): List<CheckinRow>

    @Query("SELECT * FROM checkin WHERE day_key >= :fromDay ORDER BY day_key")
    fun watchCheckinsFrom(fromDay: String): Flow<List<CheckinRow>>

    /** Plan's check-ins: only its current targets' (PlanViewModel.targetsToRead), in day order. */
    @Query("SELECT * FROM checkin WHERE target_id IN (:targetIds) ORDER BY day_key, id")
    fun watchCheckinsOf(targetIds: List<Long>): Flow<List<CheckinRow>>

    /** The re-plan flag of the latest weekly review, which PLAN_STATE repeats until the next one. */
    @Query("SELECT replan_flag FROM review WHERE kind = 'week' ORDER BY period_start DESC LIMIT 1")
    suspend fun latestWeeklyReplanFlag(): Int?

    @Query("SELECT COUNT(*) FROM checkin WHERE target_id = :targetId")
    suspend fun countCheckins(targetId: Long): Int
}
