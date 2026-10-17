package com.familycontrol.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DailyBalanceResolverTest {

    @Test
    fun parseTimestamp_withNumericLong_returnsCorrectEpoch() {
        val expected = 1729000000000L
        val result = DailyBalanceResolver.parseTimestamp(expected)
        assertEquals(expected, result)
    }

    @Test
    fun parseTimestamp_withNumericString_returnsCorrectEpoch() {
        val expected = 1729000000000L
        val result = DailyBalanceResolver.parseTimestamp("1729000000000")
        assertEquals(expected, result)
    }

    @Test
    fun parseTimestamp_withIsoUtc_returnsCorrectEpoch() {
        // "2026-10-13T04:22:50Z" -> Epoch ms
        val result = DailyBalanceResolver.parseTimestamp("2026-10-13T04:22:50Z")
        assertTrue("Epoch ms must be positive", result > 0L)
    }

    @Test
    fun parseTimestamp_withIsoOffset_returnsCorrectEpoch() {
        val result = DailyBalanceResolver.parseTimestamp("2026-10-13T04:22:50.373744+00:00")
        assertTrue("Epoch ms must be positive", result > 0L)
    }

    @Test
    fun parseTimestamp_withNullOrBlank_returnsZero() {
        assertEquals(0L, DailyBalanceResolver.parseTimestamp(null))
        assertEquals(0L, DailyBalanceResolver.parseTimestamp(""))
        assertEquals(0L, DailyBalanceResolver.parseTimestamp("   "))
        assertEquals(0L, DailyBalanceResolver.parseTimestamp("null"))
        assertEquals(0L, DailyBalanceResolver.parseTimestamp("invalid_string"))
    }

    @Test
    fun isToday_withCurrentTime_returnsTrue() {
        val now = System.currentTimeMillis()
        assertTrue(DailyBalanceResolver.isToday(now))
    }

    @Test
    fun isToday_withYesterday_returnsFalse() {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        assertFalse(DailyBalanceResolver.isToday(cal.timeInMillis))
    }

    @Test
    fun isToday_withZeroOrNegative_defaultsToTrue() {
        assertTrue(DailyBalanceResolver.isToday(0L))
        assertTrue(DailyBalanceResolver.isToday(-1L))
    }
}
