package com.jkb016.timer

import com.jkb016.timer.data.ActiveTimerState
import com.jkb016.timer.ui.TimerViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerLogicTest {

    @Test
    fun testKeypadParsing() {
        // "0500" -> 5 minutes -> 300 seconds
        assertEquals(300L, TimerViewModel.parseKeypadInputToSeconds("0500"))
        // "013000" -> 1 hour 30 min -> 5400 seconds
        assertEquals(5400L, TimerViewModel.parseKeypadInputToSeconds("013000"))
        // "45" -> 45 seconds
        assertEquals(45L, TimerViewModel.parseKeypadInputToSeconds("45"))
    }

    @Test
    fun testFormatSeconds() {
        assertEquals("5:00", TimerViewModel.formatSeconds(300L))
        assertEquals("1:00", TimerViewModel.formatSeconds(60L))
        assertEquals("1:30:00", TimerViewModel.formatSeconds(5400L))
        assertEquals("0:45", TimerViewModel.formatSeconds(45L))
    }

    @Test
    fun testDisplayOriginalLabel() {
        val timer60 = ActiveTimerState(originalSeconds = 60L)
        assertEquals("1:00 timer", timer60.displayOriginalLabel)

        val timer300 = ActiveTimerState(originalSeconds = 300L)
        assertEquals("5:00 timer", timer300.displayOriginalLabel)

        val timer5400 = ActiveTimerState(originalSeconds = 5400L)
        assertEquals("1:30:00 timer", timer5400.displayOriginalLabel)
    }

    @Test
    fun testFormatRemainingTime() {
        assertEquals("05:00", TimerViewModel.formatRemainingTime(300_000L))
        assertEquals("01:30:00", TimerViewModel.formatRemainingTime(5400_000L))
    }

    @Test
    fun testTimerProgressCalculation() {
        val timer = ActiveTimerState(
            currentTotalSeconds = 100L,
            remainingMillis = 50_000L
        )
        assertEquals(0.5f, timer.progress, 0.001f)
    }

    @Test
    fun testAddTimeDurations() {
        assertEquals("1:00", TimerViewModel.formatSeconds(60L))
        assertEquals("2:00", TimerViewModel.formatSeconds(120L))
        assertEquals("3:00", TimerViewModel.formatSeconds(180L))
        assertEquals("5:00", TimerViewModel.formatSeconds(300L))
    }
}
