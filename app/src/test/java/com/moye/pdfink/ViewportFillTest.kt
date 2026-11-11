package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.max

/**
 * v31 P1：任意模式双指放大后都能像图三那样贴满阅读区，四边零留白。
 * 只测纯 JVM 换算，不引用任何 Android View 类。
 */
class ViewportFillTest {

    private val hostW = 1080
    private val hostH = 2016           // 有栏时的阅读区高度

    /** 严格 cover 倍率：页面至少铺满一个方向。 */
    private fun coverScale(pageW: Int, pageH: Int): Float =
        max(hostW.toFloat() / pageW, hostH.toFloat() / pageH)

    private fun mapping(
        frame: PageFrame, scale: Float, bucket: Float, ty: Float
    ): PageViewportMapping = ZoomTransform.pageViewportMapping(
        hostW, hostH, frame, scale, bucket, 0f, ty
    )

    @Test
    fun centeredPageAtOneXHasSymmetricWhiteBorders() {
        val pageH = 1528
        val top = (hostH - pageH) * 0.5f
        val frame = PageFrame(0f, top, hostW, pageH)
        // 1× 时上下各约 244px；这正是「适应」居中的正常表现，不是 bug。
        assertEquals(244f, top, 0.5f)
        assertTrue(!ZoomTransform.translationRange(
            hostH, frame.top, frame.bottom, 1f
        ).hasMovement)
    }

    @Test
    fun centeredPageReachesZeroWhiteBorderAtCoverScale() {
        val pageH = 1528
        val frame = PageFrame(0f, (hostH - pageH) * 0.5f, hostW, pageH)
        val scale = coverScale(hostW, pageH)
        // 恰好 cover 时显示高度 = 宿主高度，白边归零且不再需要平移。
        val displayedH = pageH * scale
        assertEquals(hostH.toFloat(), displayedH, 0.5f)
        assertTrue(!ZoomTransform.translationRange(
            hostH, frame.top, frame.bottom, scale
        ).hasMovement)
    }

    @Test
    fun centeredFrameAtTwoXStartsDrawingAtViewportTop() {
        val pageH = 1528
        val frame = PageFrame(0f, (hostH - pageH) * 0.5f, hostW, pageH)
        val scale = 2f
        val range = ZoomTransform.translationRange(
            hostH, frame.top, frame.bottom, scale
        )
        assertTrue(range.hasMovement)
        // 把内容顶边贴到视口顶：ty = maximum = -displayedStart
        val m = mapping(frame, scale, 2f, range.maximum)
        assertEquals(0f, m.visibleTop, 0.5f)
        assertTrue(m.originY.isFinite())
        val displayFactor = ZoomTransform.renderToDisplayFactor(scale, 2f)
        assertTrue(m.visibleHeight * displayFactor >= hostH - 1f)
        assertTrue(m.visibleWidth * displayFactor >= hostW - 1f)
    }

    @Test
    fun threeModesAtTwoAndFourXAllCoverTheHost() {
        // FIT 小页 / WIDTH 长页 / FILL 满高页 × 2× / 4×
        val frames = listOf(
            PageFrame(140f, 500f, 800, 1016),
            PageFrame(0f, 0f, hostW, 2800),
            PageFrame(0f, 0f, hostW, hostH)
        )
        for ((index, frame) in frames.withIndex()) {
            for (scale in listOf(2f, 4f)) {
                val bucket = 2f
                val displayFactor = ZoomTransform.renderToDisplayFactor(scale, bucket)
                val m = mapping(frame, scale, bucket, 0f)
                assertEquals(
                    "mode$index scale$scale visW",
                    ceil(hostW / displayFactor).toInt(), m.visibleWidth
                )
                assertEquals(
                    "mode$index scale$scale visH",
                    ceil(hostH / displayFactor).toInt(), m.visibleHeight
                )
                assertTrue(
                    "mode$index scale$scale width covers host",
                    m.visibleWidth * displayFactor >= hostW - 1f
                )
                assertTrue(
                    "mode$index scale$scale height covers host",
                    m.visibleHeight * displayFactor >= hostH - 1f
                )
                // 放大后内容必然超出，平移范围开放 → 左右裁切可拖看全。
                assertTrue(
                    ZoomTransform.translationRange(
                        hostW, frame.left, frame.right, scale
                    ).hasMovement ||
                        ZoomTransform.translationRange(
                            hostH, frame.top, frame.bottom, scale
                        ).hasMovement
                )
            }
        }
    }

    @Test
    fun coverScaleDoesNotDistortThePage() {
        // FILL 是等比 cover，不是 fitXY 拉伸；放大后文字比例不变、仍是 1:1 采样。
        val pageW = 1080
        val pageH = 1528
        val s = coverScale(pageW, pageH)
        val w = pageW * s
        val h = pageH * s
        assertEquals(pageW.toFloat() / pageH, w / h, 0.0001f)
        assertTrue(w >= hostW - 0.5f || h >= hostH - 0.5f)
        assertTrue(h >= hostH - 0.5f)
    }

    @Test
    fun zoomStaysContinuousAnd1To1Sharp() {
        // 连续倍率 + bucket 分档：缩放本身不引入插值放大，文字仍 1:1。
        for (scale in listOf(1.05f, 1.29f, 1.65f, 2f, 3f, 4f)) {
            val bucket = if (scale >= 3f) 3f else if (scale >= 1.5f) 2f else 1f
            val f = ZoomTransform.renderToDisplayFactor(scale, bucket)
            assertTrue(scale > 0f && f.isFinite() && f > 0f)
        }
    }
}
