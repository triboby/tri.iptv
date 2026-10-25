package com.tritv.iptv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFilterTest {

    @Test
    fun knownAdvertAddresses() {
        assertTrue(AdFilter.isAdUrl("http://47.97.252.137:81/applive/mkt.m3u8"))
        assertTrue(AdFilter.isAdUrl("http://1.2.3.4/APPLIVE/MKT.m3u8?t=1"))
        assertTrue(AdFilter.isAdUrl("http://x.example/appadhw/index.m3u8"))
        assertFalse(AdFilter.isAdUrl("http://74.91.26.218/live/cctv4.m3u8"))
    }

    @Test
    fun shortEndedPlaylistIsAClip() {
        val clip = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\na.ts\n#EXTINF:10,\nb.ts\n#EXT-X-ENDLIST\n"
        assertTrue(AdFilter.isLoopPlaylist(clip))
        val vod = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:10,\na.ts\n"
        assertTrue(AdFilter.isLoopPlaylist(vod))
    }

    @Test
    fun longRecordingAndLiveWindowAreNotClips() {
        val long = buildString {
            append("#EXTM3U\n")
            repeat(70) { append("#EXTINF:10,\nseg$it.ts\n") }
            append("#EXT-X-ENDLIST\n")
        }
        assertFalse(AdFilter.isLoopPlaylist(long))
        assertFalse(AdFilter.isLoopPlaylist("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:6,\n7.ts\n#EXTINF:6,\n8.ts\n"))
    }

    @Test
    fun repeatedSegmentInOnePlaylistIsALoop() {
        assertTrue(AdFilter.isLoopPlaylist("#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n#EXTINF:6,\na.ts\n"))
    }

    @Test
    fun slidingLiveWindowIsNotALoop() {
        val watch = AdFilter.LoopWatch()
        assertFalse(watch.check(100, listOf("100.ts", "101.ts", "102.ts")))
        assertFalse(watch.check(101, listOf("101.ts", "102.ts", "103.ts")))
        assertFalse(watch.check(103, listOf("103.ts", "104.ts", "105.ts")))
    }

    @Test
    fun segmentsComingBackUnderNewNumbersAreALoop() {
        val watch = AdFilter.LoopWatch()
        assertFalse(watch.check(10, listOf("a.ts", "b.ts", "c.ts")))
        assertFalse(watch.check(11, listOf("b.ts", "c.ts", "d.ts")))
        assertTrue(watch.check(12, listOf("c.ts", "d.ts", "a.ts")))
    }

    @Test
    fun oldProgramDateTimeIsFrozen() {
        val now = 1_761_300_000_000L // 2025-10-24
        assertTrue(AdFilter.isFrozen("#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2023-05-01T12:00:00.000+08:00\n#EXTINF:6,\na.ts\n", now))
        assertFalse(AdFilter.isFrozen("#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2025-10-24T10:00:00Z\n#EXTINF:6,\na.ts\n", now))
        assertFalse(AdFilter.isFrozen("#EXTM3U\n#EXTINF:6,\na.ts\n", now))
        assertFalse(AdFilter.isFrozen("#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:garbage\n", now))
    }
}
