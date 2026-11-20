package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineShrinkTest {
    private val W = 1000f
    private val H = 1400f
    private val D = 3f      // density：24dp = 72px，130dp = 390px

    /** 手写一笔「之」字：折线，像素外框 l,t,r,b。 */
    private fun zig(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val p = listOf(l to t, r to (t + b) / 2, l to (t + b) / 2 + 5f, r to b, l + 10f to b - 4f, r to b + 0f)
        return FloatArray(p.size * 2) { if (it % 2 == 0) p[it / 2].first / W else p[it / 2].second / H }
    }

    @Test
    fun bigHandwritingIsShrunkAroundLeftMiddle() {
        val a = zig(100f, 300f, 200f, 420f)       // 高 120px = 40dp
        val b = zig(210f, 300f, 320f, 420f)
        val p = LineShrink.plan(listOf(a, b), W, H, D, 2)!!
        assertEquals(0.5f, p.s, 0f)
        assertEquals(100f / W, p.ax, 1e-6f)
        assertEquals(360f / H, p.ay, 1e-6f)
        val sa = LineShrink.scale(a, p, W, H)
        // 左边点不动（x=100），右边点缩到一半：320 → 100 + 220*0.5 = 210
        assertEquals(100f / W, sa[0], 1e-6f)
        val sb = LineShrink.scale(b, p, W, H)
        assertEquals(210f / W, sb[2], 1e-5f)
        // 整段缩完高度正好一半
        var t = 1f; var bt = 0f
        for (s in listOf(sa, sb)) { var i = 1; while (i < s.size) { t = minOf(t, s[i]); bt = maxOf(bt, s[i]); i += 2 } }
        assertEquals(60f, (bt - t) * H, 0.5f)
    }

    @Test
    fun smallNotesBigDrawingsAndOffAreLeftAlone() {
        val small = zig(100f, 300f, 150f, 330f)    // 高 30px = 10dp：批注
        assertNull(LineShrink.plan(listOf(small, small), W, H, D, 2))
        val huge = zig(100f, 300f, 400f, 800f)     // 高 500px：画图
        assertNull(LineShrink.plan(listOf(huge, huge), W, H, D, 2))
        val wide = zig(50f, 300f, 950f, 420f)      // 宽超过 80% 页宽
        assertNull(LineShrink.plan(listOf(wide, wide), W, H, D, 2))
        val ok = zig(100f, 300f, 200f, 420f)
        assertNull(LineShrink.plan(listOf(ok, ok), W, H, D, LineShrink.OFF))
        assertNull(LineShrink.plan(emptyList(), W, H, D, 2))
    }

    @Test
    fun singleStrokeThatIsAShapeIsNotShrunk() {
        // 一个大圈（圈字 / 画圈）：单笔、能识别成图形 → 不缩
        val n = 60
        val circle = FloatArray(2 * n) { i ->
            val k = i / 2; val t = 2 * Math.PI * k / n
            if (i % 2 == 0) ((200f + 60f * Math.cos(t)) / W).toFloat() else ((400f + 60f * Math.sin(t)) / H).toFloat()
        }
        assertNull(LineShrink.plan(listOf(circle), W, H, D, 2))
        // 一笔不像图形的连笔字：缩
        assertNotNull(LineShrink.plan(listOf(zig(100f, 300f, 200f, 420f)), W, H, D, 2))
    }

    @Test
    fun levelsAndWidth() {
        assertEquals(0.6f, LineShrink.frac(1), 0f)
        assertEquals(0.4f, LineShrink.frac(3), 0f)
        assertEquals(LineShrink.OFF, LineShrink.next(3))
        assertEquals(1, LineShrink.next(0))
        assertEquals(LineShrink.OFF, LineShrink.clamp(-5))
        // 笔宽按 √比例 变细，比例 0.5 → 0.707
        assertEquals(0.00707f, LineShrink.scaledWidth(0.01f, 0.5f), 1e-5f)
        assertTrue(LineShrink.scaledWidth(0.01f, 0.4f) > 0.4f * 0.01f)
    }
}
