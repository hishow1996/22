package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PageTimerTest {

    @Test
    fun accumulatesPerPageAcrossSwitches() {
        val t = PageTimer()
        t.start(0, 0)
        t.start(1, 10_000)       // 第 0 页 10 秒
        t.start(0, 25_000)       // 第 1 页 15 秒
        val s = t.snapshot(30_000)   // 第 0 页再 5 秒
        assertEquals(15_000L, s[0]); assertEquals(15_000L, s[1])
        assertEquals(30_000L, t.total(30_000))
    }

    @Test
    fun pauseStopsClockAndResumeContinuesSamePage() {
        val t = PageTimer()
        t.start(3, 0)
        t.pause(20_000)
        // 后台 1 小时不计
        t.resume(3_620_000)
        assertEquals(30_000L, t.snapshot(3_630_000)[3])
        // 重复 resume / pause 无害
        t.resume(3_640_000)
        t.pause(3_650_000); t.pause(3_700_000)
        // 前 20 秒 + 回前台后 3_620_000 → 3_650_000 共 30 秒 = 50 秒；多余的 resume / pause 不重复计
        assertEquals(50_000L, t.snapshot(3_800_000)[3])
    }

    @Test
    fun singleStayIsCapped() {
        val t = PageTimer(capMs = 60_000)
        t.start(0, 0)
        assertEquals(60_000L, t.snapshot(10 * 3_600_000L)[0])
    }

    @Test
    fun backwardsClockIsIgnored() {
        val t = PageTimer()
        t.start(0, 100_000)
        assertEquals(null, t.snapshot(50_000)[0])
    }

    @Test
    fun slowestIsSortedAndTieBrokenByPage() {
        val t = PageTimer()
        t.start(5, 0); t.start(2, 30_000); t.start(9, 60_000); t.start(1, 70_000)
        val top = t.slowest(80_000, 3)
        // 1 和 9 同为 10 秒，页号小的在前
        assertEquals(listOf(2 to 30_000L, 5 to 30_000L, 1 to 10_000L), top)
    }

    @Test
    fun encodeDecodeRoundTripAndGarbageIsDropped() {
        val t = PageTimer()
        t.start(0, 0); t.start(4, 5_000); t.pause(9_000)
        val text = t.encode()
        assertEquals("0:5000,4:4000", text)
        val u = PageTimer(); u.load("$text,x:1,3:abc,-1:5,2:0,7")
        assertEquals(mapOf(0 to 5_000L, 4 to 4_000L), u.snapshot(0))
    }

    @Test
    fun resetClearsAndFormatReads() {
        val t = PageTimer()
        t.start(0, 0); t.pause(5_000)
        t.reset()
        assertTrue(t.snapshot(6_000).isEmpty())
        assertEquals("45秒", PageTimer.format(45_000))
        assertEquals("3分12秒", PageTimer.format(192_000))
        assertEquals("1时05分", PageTimer.format(3_900_000))
        assertEquals("0秒", PageTimer.format(-5))
    }

    @Test
    fun dirtyFlagAndStoreRoundTrip() {
        val dir = File.createTempFile("timing", "").also { it.delete(); it.mkdirs() }
        try {
            val store = TimingStore(dir)
            val t = PageTimer()
            assertFalse(t.dirty)
            t.start(2, 0); t.pause(7_000)
            assertTrue(t.dirty)
            store.save("ab/cd:ef", t)
            assertFalse(t.dirty)
            val back = store.load("ab/cd:ef")
            assertEquals(7_000L, back.snapshot(0)[2])
            assertTrue(store.load("never-saved").snapshot(0).isEmpty())
            assertTrue(store.load("").snapshot(0).isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
