package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v41：套索命中规则从「所有采样点都在圈内」改为「碰到就中」。
 * 改前 GeometryCore.markInside 一条测试都没有，这里把会「看着圈住却选不中」
 * 的几类场景全部锁住。纯 JVM，不依赖 Android。
 */
class LassoHitTest {

    private val pad = 0.02f

    private fun stroke(vararg xy: Float, layer: Int = 0) =
        Mark(0xFF222226.toInt(), 0.01f, 255, Shapes.FREE, floatArrayOf(*xy), null, layer)

    private fun shape(pts: FloatArray, s: Int = Shapes.RECT, layer: Int = 0) =
        Mark(0xFF222226.toInt(), 0.01f, 255, s, pts, null, layer)

    /** 0.2..0.8 的正方框 */
    private val box = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)

    private fun hits(m: Mark, poly: FloatArray = box) =
        GeometryCore.markHitsLasso(m, poly, pad, pad)

    @Test
    fun onePointOutsideNoLongerDiscardsWholeStroke() {
        // 圈住 5/6，最后一个点出圈：旧规则整条作废，新规则仍选中
        val m = stroke(0.3f, 0.5f, 0.42f, 0.5f, 0.55f, 0.5f, 0.65f, 0.5f, 0.75f, 0.5f, 0.95f, 0.5f)
        assertFalse("旧规则确实判不中", GeometryCore.markInside(m, box))
        assertTrue("新规则碰到就中", hits(m))
    }

    @Test
    fun longStrokeCrossingTheLassoHits() {
        // 只有两个采样点、两端都在圈外，但线段横穿圈
        val m = stroke(0.05f, 0.5f, 0.95f, 0.5f)
        assertFalse(GeometryCore.markInside(m, box))
        assertTrue(hits(m))
    }

    @Test
    fun shapeWithOnlyTwoVerticesHitsByBounds() {
        // 矩形与圈部分重叠；橡皮是「包围盒相交即中」，套索现在同口径
        val m = shape(floatArrayOf(0.6f, 0.6f, 0.95f, 0.95f))
        assertTrue(hits(m))
    }

    @Test
    fun shapeCrossingLassoEdgeHits() {
        // 矩形四角都在圈外，但边线横穿圈
        val m = shape(floatArrayOf(0.05f, 0.45f, 0.95f, 0.55f))
        assertTrue(hits(m))
    }

    @Test
    fun farStrokeIsRejectedByBoundsCulling() {
        assertFalse(hits(stroke(0.02f, 0.02f, 0.05f, 0.05f)))
        // 粗筛之外的小圈也不误带远处笔迹
        val small = floatArrayOf(0.2f, 0.2f, 0.3f, 0.2f, 0.3f, 0.3f, 0.2f, 0.3f)
        assertFalse(hits(stroke(0.7f, 0.7f), small))
    }

    @Test
    fun pointOnBoundaryHitsThroughTolerance() {
        // 正好压在圈的左边线上，射线法可能判圈外，容差应算进
        assertTrue(hits(stroke(0.2f, 0.5f)))
    }

    @Test
    fun strokeJustOutsideButWithinToleranceHits() {
        assertTrue(hits(stroke(0.19f, 0.5f)))
    }

    @Test
    fun twoPointLassoExpandsToCapsule() {
        val raw = floatArrayOf(0.5f, 0.5f, 0.52f, 0.5f)
        val poly = GeometryCore.lassoHitPolygon(raw, pad, pad)
        assertTrue(poly.size >= 6)
        assertTrue(hits(stroke(0.51f, 0.51f), poly))
    }

    @Test
    fun singlePointLassoExpandsToSmallBox() {
        val poly = GeometryCore.lassoHitPolygon(floatArrayOf(0.5f, 0.5f), pad, pad)
        assertEquals(8, poly.size)
        assertTrue(hits(stroke(0.51f, 0.51f), poly))
    }

    @Test
    fun threeOrMorePointsArePassedThroughUnchanged() {
        assertSame(box, GeometryCore.lassoHitPolygon(box, pad, pad))
        assertArrayEquals(box, GeometryCore.lassoHitPolygon(box, pad, pad), 0f)
    }

    @Test
    fun hiddenLayerStillHitsGeometrically() {
        // 图层过滤在 InkView 之后做，几何判定本身不看 layer，
        // 这样才能区分「真没圈到」和「圈到了但图层隐藏」
        val m = stroke(0.4f, 0.4f, 0.6f, 0.6f, layer = Layers.CORRECTION)
        assertTrue(hits(m))
    }

    @Test
    fun selfIntersectingPolygonIsStable() {
        // 蝴蝶结自交：射线法奇偶规则应稳定返回且不抛异常
        val bow = floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f, 0.8f, 0.2f, 0.2f, 0.8f)
        GeometryCore.contains(bow, 0.5f, 0.5f)
        GeometryCore.markHitsLasso(stroke(0.4f, 0.5f, 0.6f, 0.5f), bow, pad, pad)
        assertTrue(GeometryCore.lassoHitPolygon(bow, pad, pad).size >= 6)
    }

    @Test
    fun segmentsIntersectDetectsCrossingAndTouching() {
        assertTrue(GeometryCore.segmentsIntersect(
            0f, 0.5f, 1f, 0.5f, 0.5f, 0f, 0.5f, 1f))
        assertFalse(GeometryCore.segmentsIntersect(
            0f, 0f, 0.2f, 0f, 0.8f, 0.8f, 1f, 0.8f))
    }
}
