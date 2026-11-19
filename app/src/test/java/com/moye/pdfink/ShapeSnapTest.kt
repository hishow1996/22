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
    fun openArcAndSquareAreNotOvals() {
        assertNull(ShapeSnap.detect(norm(ellipse(500f, 600f, 200f, 200f, sweep = PI * 1.2)), W, H))
        val sq = ArrayList<Pair<Float, Float>>()
        for (i in 0..10) sq.add(Pair(300f + i * 40f, 300f))
        for (i in 1..10) sq.add(Pair(700f, 300f + i * 40f))
        for (i in 1..10) sq.add(Pair(700f - i * 40f, 700f))
        for (i in 1..10) sq.add(Pair(300f, 700f - i * 40f))
        assertNull(ShapeSnap.detect(norm(sq), W, H))
    }

    @Test
    fun backAndForthScribbleIsNotOval() {
        // 来回描一条短线，闭合但不绕圈
        val s = (0..40).map { i -> Pair(300f + (if (i % 2 == 0) 0f else 120f), 300f + i * 2f) }
        assertNull(ShapeSnap.detect(norm(s), W, H))
        assertNotNull(ShapeSnap.detect(norm(noisyLine(100f, 100f, 400f, 100f)), W, H))
    }
}
