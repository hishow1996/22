package com.moye.pdfink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 浏览模式单指仲裁：纵向才自己接管，横向留给 ViewPager2。 */
class BrowseGestureTest {

    @Test fun `vertical drag takes over`() {
        assertTrue(GestureArbiter.isVerticalPan(4f, 60f, 16f, true))
    }

    @Test fun `horizontal drag stays with pager`() {
        assertFalse(GestureArbiter.isVerticalPan(60f, 4f, 16f, true))
    }

    @Test fun `below threshold is ignored`() {
        assertFalse(GestureArbiter.isVerticalPan(2f, 8f, 16f, true))
    }

    @Test fun `fit portrait cannot pan`() {
        // 竖屏 FIT：无纵向超出、未放大 → 单指上下不产生动作也不误触
        assertFalse(GestureArbiter.canPanY(0f, 1f))
        assertFalse(GestureArbiter.isVerticalPan(3f, 80f, 16f, false))
    }

    @Test fun `width mode and zoom can pan`() {
        assertTrue(GestureArbiter.canPanY(728f, 1f))   // 横屏 WIDTH 超出
        assertTrue(GestureArbiter.canPanY(0f, 2f))    // 竖屏已放大
    }

    @Test fun `ambiguous diagonal keeps horizontal`() {
        assertFalse(GestureArbiter.isVerticalPan(50f, 50f, 16f, true))
    }

    @Test fun `zoomed horizontal drag pans instead of nothing`() {
        // 放大时 pager 翻页被禁，单指横向必须有事可做（平移）
        assertTrue(GestureArbiter.isZoomedPan(60f, 3f, 16f, true))
        assertFalse(GestureArbiter.isZoomedPan(60f, 3f, 16f, false))
    }

    @Test fun `small jitter does not start pan`() {
        assertFalse(GestureArbiter.isZoomedPan(4f, 3f, 16f, true))
    }
}
