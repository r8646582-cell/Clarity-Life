package com.clarity.life.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The rules of the nightly maintenance (docs/MAX-PLAN.md, Phase 1, steps 4 and 9), as plain functions so they
 * are tested on the JVM: when it runs, when a backup is due, how the database check is read. The work itself is
 * in maintenance/MaintenanceRunner.kt.
 */
object Maintenance {

    /** 03:30 on the phone's clock: asleep, usually charging, and after the day ended for any day boundary. */
    const val RUN_HOUR = 3
    const val RUN_MINUTE = 30

    private const val DAY = 86_400_000L

    /**
     * A nightly run can start a little earlier than the one before (Android moves background work by minutes
     * to save battery), so "a day since the last backup" allows this much slack, or a daily backup would be
     * made only every other night.
     */
    private const val SLACK = 3 * 3_600_000L

    /** How often he can ask for a backup, in days, and how many he can keep (Settings, Your data). */
    val BACKUP_EVERY_DAYS = listOf(1, 7)
    val BACKUP_KEEP = listOf(4, 8, 14, 30)

    /** Content-free log lines older than this go: Health looks back 30 days, a year is plenty. */
    const val LOG_KEEP_DAYS = 365L

    /**
     * A reply still marked streaming this long after it began cannot be running (the gap timer stops a silent
     * reply after 120 seconds): it becomes interrupted, with its Try again button, as on a restart.
     */
    const val STALE_STREAMING_MS = 2 * 3_600_000L

    /** Real milliseconds from [now] until the next 03:30 in [tz], strictly ahead, across clock changes too. */
    fun delayToNextRun(now: Instant, tz: String): Long {
        val zone = ZoneId.of(tz)
        val local = now.atZone(zone)
        var next = local.toLocalDate().atTime(RUN_HOUR, RUN_MINUTE).atZone(zone)
        if (!next.toInstant().isAfter(now)) next = local.toLocalDate().plusDays(1).atTime(RUN_HOUR, RUN_MINUTE).atZone(zone)
        return Duration.between(now, next.toInstant()).toMillis()
    }

    /** True when the last verified backup is [everyDays] old (less the slack), or there has never been one. */
    fun backupDue(lastAt: Long?, now: Long, everyDays: Int): Boolean =
        lastAt == null || lastAt > now || now - lastAt >= everyDays * DAY - SLACK

    /** A choice outside the offered ones (a hand-edited setting, an old value) falls back to the default. */
    fun everyDays(saved: Int?, fallback: Int): Int = saved?.takeIf { it in BACKUP_EVERY_DAYS } ?: fallback

    fun keep(saved: Int?, fallback: Int): Int = saved?.takeIf { it in BACKUP_KEEP } ?: fallback

    /** SQLite's integrity_check answers with the single row "ok", or with up to N lines naming problems. */
    fun integrityOk(rows: List<String>): Boolean = rows == listOf("ok")
}
