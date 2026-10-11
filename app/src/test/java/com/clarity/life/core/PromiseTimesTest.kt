package com.clarity.life.core

import com.clarity.life.core.Vectors.intOr
import com.clarity.life.core.Vectors.str
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every case in contracts/promise_time_vectors.json, the same file tools/test_promise_times.py reads. */
class PromiseTimesTest {

    @Test
    fun everyVector() {
        val failures = mutableListOf<String>()
        val cases = Vectors.cases("contracts/promise_time_vectors.json")
        for (c in cases) {
            val args = c.getValue("args").jsonObject
            val r = PromiseTimes.resolve(
                args.str("phrase"),
                LocalDateTime.parse(args.str("now")),
                args.intOr("boundary_hour", 4),
            )
            val got = Vectors.json(
                mapOf(
                    "status" to if (r.ok) "ok" else "pick",
                    "date" to r.date?.toString(),
                    "time" to r.time?.let { "%02d:%02d".format(it.hour, it.minute) },
                ),
            )
            if (!Vectors.same(got, c.getValue("expect"))) failures.add("${c.str("name")}: got $got")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("too few vectors", cases.size >= 40)
    }

    @Test
    fun theSavedMomentIsHisLocalTime() {
        val r = PromiseTimes.resolve("tomorrow 17:00", Instant.parse("2026-10-10T16:40:00Z"), "Asia/Karachi", 4)
        assertTrue(r.ok)
        assertEquals(LocalDate.of(2026, 10, 11), r.date)
        assertEquals(LocalTime.of(17, 0), r.time)
        // 17:00 in Karachi (UTC+5) is 12:00 UTC.
        assertEquals(Instant.parse("2026-10-11T12:00:00Z"), r.at("Asia/Karachi"))
        // What the card shows before he taps.
        assertEquals("Sun 11 Oct, 5:00 pm", TimeFacts.cardTime(r.at("Asia/Karachi")!!, "Asia/Karachi"))
    }

    @Test
    fun aPickHasNoMomentToSave() {
        val r = PromiseTimes.resolve("tomorrow", Instant.parse("2026-10-10T16:40:00Z"), "Asia/Karachi", 4)
        assertEquals(null, r.at("Asia/Karachi"))
    }
}
