package com.clarity.life.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceTest {

    private val tz = "Asia/Kolkata"
    private fun at(local: String, zone: String = tz): Instant = java.time.LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant()
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun theNightlyRunIsTheNext0330OnThePhonesClock() {
        assertEquals(2 * hour + 30 * 60_000, Maintenance.delayToNextRun(at("2026-10-14T01:00"), tz))
        // At 03:30 exactly, or after it, the next run is tomorrow night.
        assertEquals(day, Maintenance.delayToNextRun(at("2026-10-14T03:30"), tz))
        assertEquals(day - 60_000, Maintenance.delayToNextRun(at("2026-10-14T03:31"), tz))
        assertEquals(4 * hour + 30 * 60_000, Maintenance.delayToNextRun(at("2026-10-14T23:00"), tz))
    }

    @Test
    fun aClockChangeNightIsCountedInRealTime() {
        // Europe/London goes back an hour at 02:00 on 25 Oct 2026: 00:00 to 03:30 is four and a half real hours.
        assertEquals(4 * hour + 30 * 60_000, Maintenance.delayToNextRun(at("2026-10-25T00:00", "Europe/London"), "Europe/London"))
        // And forward on 29 Mar 2026: 00:00 to 03:30 is two and a half real hours.
        assertEquals(2 * hour + 30 * 60_000, Maintenance.delayToNextRun(at("2026-03-29T00:00", "Europe/London"), "Europe/London"))
    }

    @Test
    fun aBackupIsDueAfterItsIntervalWithSomeSlack() {
        val now = at("2026-10-14T03:30").toEpochMilli()
        assertTrue("never backed up", Maintenance.backupDue(null, now, 7))
        assertFalse(Maintenance.backupDue(now - 6 * day, now, 7))
        assertTrue(Maintenance.backupDue(now - 7 * day, now, 7))
        // Last night's run was 40 minutes later than tonight's: still due, so daily stays daily.
        assertTrue(Maintenance.backupDue(now - day + 40 * 60_000, now, 1))
        assertFalse(Maintenance.backupDue(now - 12 * hour, now, 1))
        // A last backup "in the future" (the clock was wrong then) never blocks backups.
        assertTrue(Maintenance.backupDue(now + day, now, 7))
    }

    @Test
    fun onlyOfferedChoicesAreUsed() {
        assertEquals(1, Maintenance.everyDays(1, 7))
        assertEquals(7, Maintenance.everyDays(3, 7))
        assertEquals(7, Maintenance.everyDays(null, 7))
        assertEquals(14, Maintenance.keep(14, 4))
        assertEquals(4, Maintenance.keep(0, 4))
        assertEquals(4, Maintenance.keep(1000, 4))
    }

    @Test
    fun theDatabaseCheckIsOkOnlyWhenItSaysOk() {
        assertTrue(Maintenance.integrityOk(listOf("ok")))
        assertFalse(Maintenance.integrityOk(emptyList()))
        assertFalse(Maintenance.integrityOk(listOf("*** in database main ***", "Page 12: btreeInitPage() returns error code 11")))
        assertFalse(Maintenance.integrityOk(listOf("ok", "ok")))
    }
}
