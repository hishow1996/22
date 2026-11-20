package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeSnapTest {

    private val W = 1000f
    private val H = 1400f

    /** 像素坐标点列 → 归一化 flat。 */
    private fun norm(px: List<Pair<Float, Float>>): FloatArray {
        val out = FloatArray(px.size * 2)
        px.forEachIndexed { i, p -> out[2 * i] = p.first / W; out[2 * i + 1] = p.second / H }
        return out
    }

    private fun noisyLine(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 30, jitter: Float = 2f) =
        (0..n).map { i ->
            val t = i / n.toFloat()
            val j = if (i % 2 == 0) jitter else -jitter
            Pair(x0 + (x1 - x0) * t + j, y0 + (y1 - y0) * t - j)
        }

    @Test
    fun straightLineSnapsToEndpoints() {
        val r = ShapeSnap.detect(norm(noisyLine(100f, 200f, 500f, 450f)), W, H)
        assertTrue(r is ShapeSnap.Result.Line)
        r as ShapeSnap.Result.Line
        assertEquals(102f / W, r.x0, 0.01f)
        assertEquals(500f / W, r.x1, 0.01f)
    }

    @Test
    fun nearHorizontalAndVerticalAreStraightened() {
        val h = ShapeSnap.detect(norm(noisyLine(100f, 300f, 600f, 312f)), W, H) as ShapeSnap.Result.Line
        assertEquals(h.y0, h.y1, 0f)
        val v = ShapeSnap.detect(norm(noisyLine(300f, 100f, 310f, 700f)), W, H) as ShapeSnap.Result.Line
        assertEquals(v.x0, v.x1, 0f)
        // 斜线不摆正
        val d = ShapeSnap.detect(norm(noisyLine(100f, 100f, 500f, 400f)), W, H) as ShapeSnap.Result.Line
        assertTrue(d.y0 != d.y1 && d.x0 != d.x1)
    }

    @Test
    fun waveAndHandwritingLikeStrokesDoNotSnap() {
        val wave = (0..60).map { i -> Pair(100f + i * 8f, 300f + 40f * sin(i / 4.0).toFloat()) }
        assertNull(ShapeSnap.detect(norm(wave), W, H))
        val zig = (0..40).map { i -> Pair(100f + i * 6f, 300f + if (i % 4 < 2) 0f else 50f) }
        assertNull(ShapeSnap.detect(norm(zig), W, H))
    }

    @Test
    fun tooShortOrTooFewPointsDoNotSnap() {
        assertNull(ShapeSnap.detect(norm(noisyLine(100f, 100f, 120f, 110f)), W, H))
        assertNull(ShapeSnap.detect(norm(listOf(Pair(0f, 0f), Pair(100f, 0f))), W, H))
        assertNull(ShapeSnap.detect(FloatArray(0), W, H))
    }

    private fun ellipse(cx: Float, cy: Float, a: Float, b: Float, sweep: Double = 2 * PI, noise: Float = 3f, n: Int = 60) =
        (0..n).map { i ->
            val t = sweep * i / n
            val j = if (i % 2 == 0) noise else -noise
            Pair(cx + (a + j) * cos(t).toFloat(), cy + (b + j) * sin(t).toFloat())
        }

    @Test
    fun closedLoopBecomesOval() {
        val r = ShapeSnap.detect(norm(ellipse(500f, 600f, 200f, 100f)), W, H)
        assertTrue(r is ShapeSnap.Result.Oval)
        r as ShapeSnap.Result.Oval
        assertEquals(300f / W, r.l, 0.02f)
        assertEquals(700f / W, r.r, 0.02f)
        assertEquals(500f / H, r.t, 0.02f)
        assertEquals(700f / H, r.b, 0.02f)
    }

    @Test
    fun almostRoundBecomesCircle() {
        val r = ShapeSnap.detect(norm(ellipse(500f, 600f, 150f, 140f, noise = 1f)), W, H) as ShapeSnap.Result.Oval
        // 正圆：宽高（像素）相等
        assertEquals((r.r - r.l) * W, (r.b - r.t) * H, 0.5f)
    }

    @Test
    fun openArcIsNotAShapeAndSquareIsNoLongerAnOval() {
        assertNull(ShapeSnap.detect(norm(ellipse(500f, 600f, 200f, 200f, sweep = PI * 1.2)), W, H))
        // v49：方框不再被当成「不是圆就不识别」，而是识别成矩形（见 closedSquareBecomesSquareRect）
        val sq = ArrayList<Pair<Float, Float>>()
        for (i in 0..10) sq.add(Pair(300f + i * 40f, 300f))
        for (i in 1..10) sq.add(Pair(700f, 300f + i * 40f))
        for (i in 1..10) sq.add(Pair(700f - i * 40f, 700f))
        for (i in 1..10) sq.add(Pair(300f, 700f - i * 40f))
        assertTrue(ShapeSnap.detect(norm(sq), W, H) !is ShapeSnap.Result.Oval)
    }

    @Test
    fun backAndForthScribbleIsNotOval() {
        // 来回描一条短线，闭合但不绕圈
        val s = (0..40).map { i -> Pair(300f + (if (i % 2 == 0) 0f else 120f), 300f + i * 2f) }
        assertNull(ShapeSnap.detect(norm(s), W, H))
        assertNotNull(ShapeSnap.detect(norm(noisyLine(100f, 100f, 400f, 100f)), W, H))
    }

    // ---------- v49：长方形 / 正方形 / 三角形 / 斜四边形 ----------

    /** 沿折线闭合路径按固定步长取点，叠加少量抖动、让首尾留一点缺口或出头。 */
    private fun loop(verts: List<Pair<Float, Float>>, jitter: Float = 2f, gapFrac: Float = 0.02f): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val k = verts.size
        var total = 0f
        for (j in 0 until k) { val q = verts[(j + 1) % k]; total += kotlin.math.hypot(q.first - verts[j].first, q.second - verts[j].second) }
        val stop = total * (1f - gapFrac)
        var walked = 0f
        var n = 0
        for (j in 0 until k) {
            val p = verts[j]; val q = verts[(j + 1) % k]
            val len = kotlin.math.hypot(q.first - p.first, q.second - p.second)
            var s = 0f
            while (s < len && walked + s < stop) {
                val t = s / len
                val jx = if (n % 2 == 0) jitter else -jitter
                out.add(Pair(p.first + (q.first - p.first) * t + jx, p.second + (q.second - p.second) * t - jx))
                s += 9f; n++
            }
            walked += len
        }
        return out
    }

    private fun rectVerts(l: Float, t: Float, r: Float, b: Float) =
        listOf(Pair(l, t), Pair(r, t), Pair(r, b), Pair(l, b))

    @Test
    fun closedRectangleBecomesRect() {
        val r = ShapeSnap.detect(norm(loop(rectVerts(300f, 400f, 800f, 650f))), W, H)
        assertTrue(r is ShapeSnap.Result.Rect)
        r as ShapeSnap.Result.Rect
        assertEquals(300f / W, r.l, 0.02f)
        assertEquals(800f / W, r.r, 0.02f)
        assertEquals(400f / H, r.t, 0.02f)
        assertEquals(650f / H, r.b, 0.02f)
        // 长宽差很多：不会被压成正方形
        assertTrue((r.r - r.l) * W > 1.5f * (r.b - r.t) * H)
    }

    @Test
    fun closedSquareBecomesSquareRect() {
        val r = ShapeSnap.detect(norm(loop(rectVerts(300f, 300f, 600f, 590f))), W, H) as ShapeSnap.Result.Rect
        // 长宽相差不到 12%：取正方形（像素宽 = 像素高）
        assertEquals((r.r - r.l) * W, (r.b - r.t) * H, 0.5f)
    }

    @Test
    fun slightlyTiltedAndSloppyRectangleStillBecomesAxisAlignedRect() {
        val a = Math.toRadians(9.0).toFloat()
        val c = Pair(550f, 525f)
        val tilted = rectVerts(300f, 400f, 800f, 650f).map {
            val dx = it.first - c.first; val dy = it.second - c.second
            Pair(c.first + dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a), c.second + dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a))
        }
        val r = ShapeSnap.detect(norm(loop(tilted, jitter = 4f, gapFrac = 0.08f)), W, H)
        assertTrue(r is ShapeSnap.Result.Rect)
    }

    @Test
    fun closedTriangleBecomesPolyWithThreeVertices() {
        val tri = listOf(Pair(500f, 300f), Pair(750f, 700f), Pair(250f, 700f))
        val r = ShapeSnap.detect(norm(loop(tri)), W, H)
        assertTrue(r is ShapeSnap.Result.Poly)
        r as ShapeSnap.Result.Poly
        assertEquals(3, r.pts.size / 2)
        // 每个顶点都在真实顶点附近（像素 20 以内）
        for (v in tri) {
            val near = (0 until 3).any { i ->
                kotlin.math.hypot(r.pts[2 * i] * W - v.first, r.pts[2 * i + 1] * H - v.second) < 20f
            }
            assertTrue(near)
        }
    }

    @Test
    fun tiltedQuadrilateralAndDiamondBecomePolyWithFourVertices() {
        val diamond = listOf(Pair(500f, 300f), Pair(700f, 500f), Pair(500f, 700f), Pair(300f, 500f))
        val r = ShapeSnap.detect(norm(loop(diamond)), W, H)
        assertTrue(r is ShapeSnap.Result.Poly)
        assertEquals(4, (r as ShapeSnap.Result.Poly).pts.size / 2)
    }

    @Test
    fun polygonStrokeIsClosedDenseAndOnTheEdges() {
        val poly = floatArrayOf(0.3f, 0.3f, 0.6f, 0.3f, 0.45f, 0.5f)
        val s = ShapeSnap.polygonStroke(poly, W, H, stepPx = 4f)
        assertEquals(s[0], s[s.size - 2], 0f)               // 首尾相接
        assertEquals(s[1], s[s.size - 1], 0f)
        assertTrue(s.size / 2 > 100)                         // 够密
        // 第一条边上的点 y 恒为 0.3
        for (i in 0 until 20) assertEquals(0.3f, s[2 * i + 1], 1e-6f)
    }

    @Test
    fun openShapesAndHandwritingLikeStrokesAreNotPolygons() {
        // V 字、Z 字、L 形：不闭合
        val v = ArrayList<Pair<Float, Float>>()
        for (i in 0..15) v.add(Pair(300f + i * 15f, 300f + i * 20f))
        for (i in 1..15) v.add(Pair(525f + i * 15f, 600f - i * 20f))
        assertNull(ShapeSnap.detect(norm(v), W, H))
        val z = ArrayList<Pair<Float, Float>>()
        for (i in 0..15) z.add(Pair(300f + i * 20f, 300f))
        for (i in 1..15) z.add(Pair(600f - i * 20f, 300f + i * 20f))
        for (i in 1..15) z.add(Pair(300f + i * 20f, 600f))
        assertNull(ShapeSnap.detect(norm(z), W, H))
        val l = ArrayList<Pair<Float, Float>>()
        for (i in 0..15) l.add(Pair(300f, 300f + i * 20f))
        for (i in 1..15) l.add(Pair(300f + i * 20f, 600f))
        assertNull(ShapeSnap.detect(norm(l), W, H))
    }

    @Test
    fun thinRectangleAroundASentenceIsStillARect() {
        val r = ShapeSnap.detect(norm(loop(rectVerts(200f, 500f, 900f, 580f), jitter = 1.5f)), W, H)
        assertTrue(r is ShapeSnap.Result.Rect)
    }
}
