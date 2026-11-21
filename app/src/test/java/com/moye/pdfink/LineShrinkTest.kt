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
        val p = LineShrink.plan(listOf(a, b), W, H, D, 50)!!
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
        assertNull(LineShrink.plan(listOf(small, small), W, H, D, 50))
        val huge = zig(100f, 300f, 400f, 800f)     // 高 500px：画图
        assertNull(LineShrink.plan(listOf(huge, huge), W, H, D, 50))
        val wide = zig(50f, 300f, 950f, 420f)      // 宽超过 80% 页宽
        assertNull(LineShrink.plan(listOf(wide, wide), W, H, D, 50))
        val ok = zig(100f, 300f, 200f, 420f)
        assertNull(LineShrink.plan(listOf(ok, ok), W, H, D, LineShrink.OFF))
        assertNull(LineShrink.plan(emptyList(), W, H, D, 50))
    }

    @Test
    fun singleStrokeThatIsAShapeIsNotShrunk() {
        // 一个大圈（圈字 / 画圈）：单笔、能识别成图形 → 不缩
        val n = 60
        val circle = FloatArray(2 * n) { i ->
            val k = i / 2; val t = 2 * Math.PI * k / n
            if (i % 2 == 0) ((200f + 60f * Math.cos(t)) / W).toFloat() else ((400f + 60f * Math.sin(t)) / H).toFloat()
        }
        assertNull(LineShrink.plan(listOf(circle), W, H, D, 50))
        // 一笔不像图形的连笔字：缩
        assertNotNull(LineShrink.plan(listOf(zig(100f, 300f, 200f, 420f)), W, H, D, 50))
    }

    @Test
    fun percentIsClampedSnappedAndOffStaysOff() {
        assertEquals(LineShrink.OFF, LineShrink.clamp(0))
        assertEquals(LineShrink.OFF, LineShrink.clamp(-5))
        assertEquals(30, LineShrink.clamp(1))          // 非零但太小：夹到下限，不会悄悄变成「关」
        assertEquals(90, LineShrink.clamp(250))
        assertEquals(55, LineShrink.clamp(54))         // 对齐到 5% 步长
        assertEquals(50, LineShrink.clamp(52))
        assertEquals(0.6f, LineShrink.frac(60), 1e-6f)
        assertEquals(0.4f, LineShrink.frac(40), 1e-6f)
        assertEquals(1f, LineShrink.frac(LineShrink.OFF), 0f)
        assertEquals(0.35f, LineShrink.frac(35), 1e-6f)
    }

    @Test
    fun legacyLevelsMigrateToTheSamePercentages() {
        assertEquals(LineShrink.OFF, LineShrink.fromLegacyLevel(0))
        assertEquals(60, LineShrink.fromLegacyLevel(1))
        assertEquals(50, LineShrink.fromLegacyLevel(2))
        assertEquals(40, LineShrink.fromLegacyLevel(3))
        assertEquals(LineShrink.OFF, LineShrink.fromLegacyLevel(99))
        // 预设值本身都是合法百分比
        for (p in LineShrink.PRESETS) assertEquals(p, LineShrink.clamp(p))
    }

    @Test
    fun arbitraryPercentDrivesThePlanScale() {
        val a = zig(100f, 300f, 200f, 420f)
        val b = zig(210f, 300f, 320f, 420f)
        assertEquals(0.35f, LineShrink.plan(listOf(a, b), W, H, D, 35)!!.s, 1e-6f)
        assertEquals(0.8f, LineShrink.plan(listOf(a, b), W, H, D, 80)!!.s, 1e-6f)
        assertNull(LineShrink.plan(listOf(a, b), W, H, D, 0))
    }

    @Test
    fun widthShrinksBySquareRootOfTheScale() {
        // 笔宽按 √比例 变细，比例 0.5 → 0.707
        assertEquals(0.00707f, LineShrink.scaledWidth(0.01f, 0.5f), 1e-5f)
        assertTrue(LineShrink.scaledWidth(0.01f, 0.4f) > 0.4f * 0.01f)
    }
}
