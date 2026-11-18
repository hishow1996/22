package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCropTest {

    private val W = 100
    private val H = 140

    private fun page(bg: Int = 0xFFFFFFFF.toInt()): IntArray = IntArray(W * H) { bg }

    private fun fill(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in y0 until y1) for (x in x0 until x1) px[y * W + x] = c
    }

    @Test
    fun findsContentBox() {
        val px = page()
        fill(px, 20, 30, 80, 100, 0xFF000000.toInt())
        val b = AutoCrop.findBounds(px, W, H)!!
        assertEquals(0.20f, b[0], 0.001f)
        assertEquals(30f / H, b[1], 0.001f)
        assertEquals(0.80f, b[2], 0.001f)
        assertEquals(100f / H, b[3], 0.001f)
    }

    @Test
    fun blankPageAndFullPageGiveNull() {
        assertNull(AutoCrop.findBounds(page(), W, H))
        val px = page()
        fill(px, 0, 0, W, H, 0xFF000000.toInt())
        // 整页同色：背景就是它，等于空白
        assertNull(AutoCrop.findBounds(px, W, H))
        val full = page()
        fill(full, 1, 1, W - 1, H - 1, 0xFF202020.toInt())
        assertNull(AutoCrop.findBounds(full, W, H))
    }

    @Test
    fun coloredBackgroundIsTreatedAsBlank() {
        val cream = 0xFFF3E6C8.toInt()
        val px = page(cream)
        fill(px, 10, 10, 90, 20, 0xFF222222.toInt())
        val b = AutoCrop.findBounds(px, W, H)!!
        assertEquals(0.10f, b[0], 0.001f)
        assertEquals(0.90f, b[2], 0.001f)
        assertEquals(10f / H, b[1], 0.001f)
        assertEquals(20f / H, b[3], 0.001f)
    }

    @Test
    fun isolatedSpecksAreIgnored() {
        val px = page()
        fill(px, 30, 40, 70, 90, 0xFF000000.toInt())
        px[5 * W + 5] = 0xFF000000.toInt()          // 角落孤立噪点
        px[130 * W + 95] = 0xFF000000.toInt()
        val b = AutoCrop.findBounds(px, W, H)!!
        assertEquals(0.30f, b[0], 0.001f)
        assertEquals(0.70f, b[2], 0.001f)
        assertEquals(40f / H, b[1], 0.001f)
        assertEquals(90f / H, b[3], 0.001f)
    }

    @Test
    fun tinyContentIsNotCropped() {
        val px = page()
        fill(px, 45, 130, 52, 134, 0xFF000000.toInt())   // 只有页码
        assertNull(AutoCrop.findBounds(px, W, H))
        assertNull(AutoCrop.findBounds(IntArray(4), 2, 2))
    }

    @Test
    fun planFillsWidthAndCentersContent() {
        // 屏宽 1000，页宽 = 屏宽；内容占 20%~80% 宽
        val b = floatArrayOf(0.2f, 0.1f, 0.8f, 0.9f)
        val p = AutoCrop.plan(b, 1000, 1600, 0f, 0f, 1000, 1400)!!
        // 内容宽 600 + 两侧留白 2*20 = 640 → 1000/640 = 1.5625
        assertEquals(1000f / 640f, p.scale, 0.001f)
        // 内容中心 500 已在屏幕中心，不需要横移
        assertEquals(0f, p.tx, 0.001f)
        // 内容上缘 140，留白 20 → 120；放大后应落在屏幕顶 0
        val shownTop = 800f + (120f - 800f) * p.scale + p.ty
        assertEquals(0f, shownTop, 0.01f)
    }

    @Test
    fun planShiftsOffCenterContent() {
        val b = floatArrayOf(0.1f, 0.0f, 0.5f, 0.5f)    // 内容偏左
        val p = AutoCrop.plan(b, 1000, 1000, 0f, 0f, 1000, 1000)!!
        val cx = 300f                                    // (0.1+0.5)/2*1000
        val shownCx = 500f + (cx - 500f) * p.scale + p.tx
        assertEquals(500f, shownCx, 0.01f)
    }

    @Test
    fun planSkipsWhenNothingToGainAndCapsScale() {
        assertNull(AutoCrop.plan(floatArrayOf(0.02f, 0f, 0.98f, 1f), 1000, 1000, 0f, 0f, 1000, 1000))
        val narrow = AutoCrop.plan(floatArrayOf(0.4f, 0f, 0.5f, 0.2f), 1000, 1000, 0f, 0f, 1000, 1000)
        assertNotNull(narrow)
        assertEquals(2.5f, narrow!!.scale, 0.0001f)
        assertNull(AutoCrop.plan(floatArrayOf(0.1f, 0f), 1000, 1000, 0f, 0f, 1000, 1000))
        assertNull(AutoCrop.plan(floatArrayOf(0.1f, 0f, 0.5f, 1f), 0, 1000, 0f, 0f, 1000, 1000))
        assertTrue(true)
    }
}
