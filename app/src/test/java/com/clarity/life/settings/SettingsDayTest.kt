package com.clarity.life.settings

import com.clarity.life.data.entity.SettingRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun aChangeOfThePhonesZoneReachesTheScreens() = runBlocking {
        val rows = MutableStateFlow(emptyList<SettingRow>())
        val zone = MutableStateFlow("Asia/Karachi")
        val firstSeen = CompletableDeferred<Unit>()
        val got = async {
            SettingsRepository.watchDay(rows, zone).onEach { firstSeen.complete(Unit) }.take(2).map { it.timeZone }.toList()
        }
        firstSeen.await()
        zone.value = "Europe/London" // landed after a flight
        assertEquals(listOf("Asia/Karachi", "Europe/London"), withTimeout(5_000) { got.await() })
    }

    @Test
    fun aStoredTimeZoneStillWinsWhenThePhoneMoves() = runBlocking {
        val rows = MutableStateFlow(listOf(SettingRow(SettingsRepository.Keys.TIME_ZONE, "Asia/Karachi")))
        val zone = MutableStateFlow("Europe/London")
        assertEquals("Asia/Karachi", SettingsRepository.watchDay(rows, zone).first().timeZone)
    }
}
