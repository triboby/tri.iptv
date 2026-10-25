package com.tritv.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineRankerTest {

    private val urls = listOf("a", "b", "c", "d")

    @Test
    fun fastestWorkingFirstThenUncheckedThenFailed() {
        val stats = mapOf(
            "a" to LineStat(failAt = 5),
            "b" to LineStat(latencyMs = 900, okAt = 5),
            "d" to LineStat(latencyMs = 120, okAt = 5),
        )
        assertEquals(listOf(3, 1, 2, 0), LineRanker.order(urls, stats, picked = null))
    }

    @Test
    fun recoveredLineCountsAsWorking() {
        val stats = mapOf("a" to LineStat(latencyMs = 50, okAt = 9, failAt = 5), "b" to LineStat(latencyMs = 80, okAt = 5))
        assertEquals(listOf(0, 1, 2, 3), LineRanker.order(urls, stats, picked = null))
    }

    @Test
    fun viewersPickComesFirstUntilItFails() {
        val stats = mapOf("a" to LineStat(latencyMs = 50, okAt = 5), "c" to LineStat(latencyMs = 400, okAt = 5))
        assertEquals(listOf(2, 0, 1, 3), LineRanker.order(urls, stats, picked = "c"))
        val failed = stats + ("c" to LineStat(latencyMs = 400, okAt = 5, failAt = 6))
        assertEquals(listOf(0, 1, 3, 2), LineRanker.order(urls, failed, picked = "c"))
    }

    @Test
    fun playbackSuccessWithoutLatencyRanksAfterMeasuredLines() {
        val stats = mapOf("a" to LineStat(okAt = 5), "b" to LineStat(latencyMs = 300, okAt = 5))
        assertEquals(listOf(1, 0, 2, 3), LineRanker.order(urls, stats, picked = null))
    }

    @Test
    fun failedLinesThatWorkedRecentlyComeFirst() {
        val stats = mapOf(
            "a" to LineStat(failAt = 9),
            "b" to LineStat(okAt = 3, failAt = 9),
            "c" to LineStat(okAt = 7, failAt = 9),
            "d" to LineStat(failAt = 9),
        )
        assertEquals(listOf(2, 1, 0, 3), LineRanker.order(urls, stats, picked = null))
    }

    @Test
    fun latencyIsSmoothed() {
        val s = LineStat().succeeded(1, 100).succeeded(2, 300)
        assertEquals(200, s.latencyMs)
        assertEquals(200, s.failed(3).succeeded(4).latencyMs)
    }

    @Test
    fun encodesRoundTrip() {
        val s = LineStat(latencyMs = 85, okAt = 10, failAt = 3)
        assertEquals(s, LineRanker.decode(LineRanker.encode(s)))
        assertEquals(LineStat(okAt = 0, failAt = 7), LineRanker.decode(LineRanker.encode(LineStat(failAt = 7))))
        assertNull(LineRanker.decode("junk"))
    }

    @Test
    fun onlyHttpIsProbed() {
        assertEquals(true, LineRanker.probeable("HTTP://a/1.m3u8"))
        assertEquals(false, LineRanker.probeable("rtsp://a/1"))
    }

    @Test
    fun advertLinesGoAfterCleanOnesAndLastWhileStillShowingIt() {
        val now = 10 * LineStat.AD_HOLD_MS
        val stats = mapOf(
            // Latest check was the advert.
            "a" to LineStat(okAt = now - 2, failAt = now - 1, adAt = now - 1),
            "b" to LineStat(failAt = now - 5),
            // Showed an advert, worked since.
            "c" to LineStat(latencyMs = 50, okAt = now - 1, failAt = now - 2, adAt = now - 2),
            "d" to LineStat(latencyMs = 900, okAt = now - 1),
        )
        assertEquals(listOf(3, 2, 1, 0), LineRanker.order(urls, stats, picked = "a", now = now))
        // Picked and working since its advert: still first.
        assertEquals(listOf(2, 3, 1, 0), LineRanker.order(urls, stats, picked = "c", now = now))
        // Once the hold is over they rank on their latest result again.
        assertEquals(listOf(2, 3, 0, 1), LineRanker.order(urls, stats, picked = null, now = now + LineStat.AD_HOLD_MS))
    }

    @Test
    fun advertTimeSurvivesEncodingAndOldFormReads() {
        val stat = LineStat(latencyMs = 80, okAt = 3, failAt = 4, adAt = 4)
        assertEquals(stat, LineRanker.decode(LineRanker.encode(stat)))
        assertEquals(LineStat(latencyMs = 80, okAt = 3, failAt = 4), LineRanker.decode("80|3|4"))
    }
}
