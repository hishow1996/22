package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeTest {

    @Test
    fun portraitAlwaysForcesFit() {
        for (m in DisplayMode.FIT..DisplayMode.DOUBLE) {
            assertEquals(DisplayMode.FIT, DisplayMode.effective(m, false))
        }
        assertEquals(DisplayMode.DOUBLE, DisplayMode.effective(DisplayMode.DOUBLE, true))
        assertEquals(DisplayMode.WIDTH, DisplayMode.effective(DisplayMode.WIDTH, true))
    }

    @Test
    fun doubleModeGroupsTwoPagesPerItem() {
        assertEquals(8, DisplayMode.viewCount(DisplayMode.DOUBLE, true, 15))
        assertEquals(15, DisplayMode.viewCount(DisplayMode.FIT, true, 15))
        assertEquals(8, DisplayMode.logical(DisplayMode.DOUBLE, true, 4))
        assertEquals(4, DisplayMode.viewPos(DisplayMode.DOUBLE, true, 8))
        assertEquals(4, DisplayMode.logical(DisplayMode.FIT, true, 4))
        // 每组主页必为偶数，保证相邻 item 的笔迹键不重叠
        for (g in 0 until 8) {
            assertEquals(g * 2, DisplayMode.pages(
                DisplayMode.DOUBLE, true, g, 15)[0])
        }
    }

    @Test
    fun pagesPairOnlyWhenNextPageExists() {
        assertArrayEquals(intArrayOf(8, 9),
            DisplayMode.pages(DisplayMode.DOUBLE, true, 4, 15))
        assertArrayEquals(intArrayOf(14),
            DisplayMode.pages(DisplayMode.DOUBLE, true, 7, 15))
        assertArrayEquals(intArrayOf(4),
            DisplayMode.pages(DisplayMode.FIT, true, 4, 15))
    }

    @Test
    fun labelShowsPageRangeInDoubleMode() {
        assertEquals("9–10 / 15", DisplayMode.label(DisplayMode.DOUBLE, true, 4, 15))
        assertEquals("15 / 15", DisplayMode.label(DisplayMode.DOUBLE, true, 7, 15))
        assertEquals("5 / 15", DisplayMode.label(DisplayMode.WIDTH, true, 4, 15))
    }

    @Test
    fun doublePageAndSpreadAreMutuallyExclusive() {
        val s = UiState()
        s.landscape = true
        s.spread = true
        s.displayMode = DisplayMode.FIT
        assertTrue(s.effectiveSpread)
        s.displayMode = DisplayMode.DOUBLE
        assertFalse(s.effectiveSpread)
        // 用户设置不丢失，切回 FIT 自动恢复对开
        s.displayMode = DisplayMode.FIT
        assertTrue(s.effectiveSpread)
    }

    @Test
    fun sessionRoundTripKeepsModeAndPan() {
        val s = DocSession(displayMode = DisplayMode.WIDTH,
            panX = 12.5f, panY = -80f)
        val back = DocSession.fromJson(s.toJson())
        assertEquals(DisplayMode.WIDTH, back.displayMode)
        assertEquals(12.5f, back.panX, 0.0001f)
        assertEquals(-80f, back.panY, 0.0001f)
    }

    @Test
    fun legacySessionDefaultsToFitWithZeroPan() {
        val back = DocSession.fromJson(org.json.JSONObject("{}"))
        assertEquals(DisplayMode.FIT, back.displayMode)
        assertEquals(0f, back.panX, 0.0001f)
        assertEquals(0f, back.panY, 0.0001f)
    }
}
