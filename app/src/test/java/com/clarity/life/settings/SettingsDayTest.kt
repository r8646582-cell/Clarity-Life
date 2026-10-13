package com.clarity.life.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The live day that Talk, Plan and Promises follow reads the settings rows the same way the coach does. */
class SettingsDayTest {

    @Test
    fun aChangedDayBoundaryIsReadFromTheStoredRows() {
        val day = SettingsRepository.day(mapOf(SettingsRepository.Keys.DAY_BOUNDARY_HOUR to "2"))
        assertEquals(2, day.boundaryHour)
    }

    @Test
    fun nothingStoredMeansTheDefaults() {
        val day = SettingsRepository.day(emptyMap())
        assertEquals(DefaultSettings.DAY_BOUNDARY_HOUR, day.boundaryHour)
        assertEquals(java.util.TimeZone.getDefault().id, day.timeZone)
    }

    @Test
    fun anUnreadableBoundaryFallsBackToTheDefault() {
        val day = SettingsRepository.day(mapOf(SettingsRepository.Keys.DAY_BOUNDARY_HOUR to "late"))
        assertEquals(DefaultSettings.DAY_BOUNDARY_HOUR, day.boundaryHour)
    }

    @Test
    fun aStoredTimeZoneWinsOverThePhones() {
        val day = SettingsRepository.day(mapOf(SettingsRepository.Keys.TIME_ZONE to "Asia/Karachi"))
        assertEquals("Asia/Karachi", day.timeZone)
    }
}
