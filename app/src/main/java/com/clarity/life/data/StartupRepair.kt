package com.clarity.life.data

import com.clarity.life.data.dao.DiagnosticsDao
import com.clarity.life.data.dao.RepairDao
import com.clarity.life.data.entity.ErrorLogRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The repair sweep that runs once on every start (spec section 18, point 6): a reply the process died in
 * the middle of becomes `interrupted` (with a Try again button), and a job left `running` becomes
 * `pending`, so it is simply picked up again. No spinner can stay forever.
 *
 * Both moves are in the allowed transitions of contracts/states.json; [StateMachines] proves it.
 */
@Singleton
class StartupRepair @Inject constructor(
    private val repair: RepairDao,
    private val diagnostics: DiagnosticsDao,
) {
    suspend fun run(now: Long): Result {
        StateMachines.move(StateMachines.MESSAGE, "streaming", "interrupted")
        StateMachines.move(StateMachines.JOB, "running", "pending")
        val messages = repair.interruptStreamingMessages()
        val jobs = repair.requeueRunningJobs(now - STALE_RUNNING_MS)
        if (messages > 0 || jobs > 0) {
            // Counts only: a log line in this app never carries a word of what was said.
            diagnostics.log(
                ErrorLogRow(at = now, area = "startup", code = "repair_sweep", detail = "m=$messages j=$jobs"),
            )
        }
        return Result(messages, jobs)
    }

    data class Result(val repairedMessages: Int, val requeuedJobs: Int)

    private companion object {
        /** The same staleness the reflection and writing jobs use before they take over a running row. */
        const val STALE_RUNNING_MS = 30 * 60 * 1000L
    }
}
