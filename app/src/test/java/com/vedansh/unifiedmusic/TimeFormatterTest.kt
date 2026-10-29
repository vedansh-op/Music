package com.vedansh.unifiedmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {
    @Test
    fun formatsZeroAndNegativeValuesAsZero() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:00", formatPlaybackTime(-5_000L))
    }

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("2:05", formatPlaybackTime(125_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_000L))
    }

    @Test
    fun formatsHoursWhenNeeded() {
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
    }
}
