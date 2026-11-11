package com.moye.pdfink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v26 竖屏上下滑：竖屏 pager 切 VERTICAL 后，纵向必须让给系统 RecyclerView，
 * 横向补一次离散翻页，放大态仍由 ZoomHost 做页内平移。
 * 这里验纯函数仲裁，保证三条路径互斥、不抢不吞。
 */
class VerticalScrollTest {

    private val t = 16f

    @Test
    fun `portrait 1x vertical drag is handed to pager`() {
        // 竖屏 FILL：panLimitY≈0 → canPan=false；未放大 → zoomed=false
        val dxAbs = 4f
        val dyAbs = 80f
        assertFalse(GestureArbiter.isVerticalPan(dxAbs, dyAbs, t, false))
        assertFalse(GestureArbiter.isZoomedPan(dxAbs, dyAbs, t, false))
        assertFalse(GestureArbiter.isHorizontalFlip(dxAbs, dyAbs, t, true))
    }

    @Test
    fun `portrait 1x horizontal drag flips once`() {
        assertTrue(GestureArbiter.isHorizontalFlip(90f, 5f, t, true))
        assertFalse(GestureArbiter.isHorizontalFlip(10f, 5f, t, true))   // 阈值内
        assertFalse(GestureArbiter.isHorizontalFlip(5f, 90f, t, true))   // 纵向不翻
    }

    @Test
    fun `slight diagonal is not a flip`() {
        // 1.2 倍方向比：手写斜划不误翻页
        assertFalse(GestureArbiter.isHorizontalFlip(20f, 18f, t, true))
        assertTrue(GestureArbiter.isHorizontalFlip(24f, 18f, t, true))
    }

    @Test
    fun `zoomed page keeps in-page pan and disables flip`() {
        assertTrue(GestureArbiter.isZoomedPan(30f, 6f, t, true))
        assertTrue(GestureArbiter.isZoomedPan(6f, 30f, t, true))
        // 放大态 enabled=false，横向不触发离散翻页
        assertFalse(GestureArbiter.isHorizontalFlip(90f, 5f, t, false))
    }

    @Test
    fun `landscape WIDTH vertical overflow still pans inside page`() {
        assertTrue(GestureArbiter.isVerticalPan(4f, 80f, t, true))
        assertFalse(GestureArbiter.isVerticalPan(80f, 4f, t, true))
        assertTrue(GestureArbiter.canPanY(240f, 1f))
        assertFalse(GestureArbiter.canPanY(0f, 1f))
    }
}
