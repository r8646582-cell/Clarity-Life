package com.clarity.life.ui.know

import org.junit.Assert.assertEquals
import org.junit.Test

class GridStepTest {

    @Test
    fun aSmallChartStepsByOne() {
        assertEquals(1.0, gridStep(2.0), 0.0)
        assertEquals(1.0, gridStep(3.0), 0.0)
    }

    @Test
    fun theStepIsARoundNumberGivingAboutThreeLines() {
        assertEquals(10.0, gridStep(28.0), 0.0) // a week of 4 a day: lines at 0, 10, 20, 30
        assertEquals(5.0, gridStep(12.0), 0.0)
        assertEquals(50.0, gridStep(120.0), 0.0) // a month of 4 a day
        assertEquals(2.0, gridStep(6.0), 0.0)
    }

    @Test
    fun theStepNeverLeavesMoreThanFourLinesAboveZero() {
        listOf(1.0, 4.0, 7.0, 15.0, 28.0, 33.0, 61.0, 99.0, 120.0, 365.0, 1000.0, 2500.0).forEach { most ->
            val step = gridStep(most)
            val lines = kotlin.math.ceil(most / step)
            assert(lines in 1.0..4.0) { "most=$most step=$step lines=$lines" }
        }
    }
}
