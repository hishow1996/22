package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSnapTest {

    private val W = 1000f
    private val H = 1400f

    /** 两行字：每字宽 0.03、高 0.02，行 1 的 y=0.30..0.32，行 2 的 y=0.36..0.38，各 20 字，左起 0.10。 */
    private fun layout(): PageTextLayout {
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        val real = ArrayList<Boolean>()
        for ((row, top) in listOf(0.30f, 0.36f).withIndex()) {
            for (c in 0 until 20) {
                sb.append('字'); real.add(true)
                val x0 = 0.10f + c * 0.03f
                boxes.add(x0); boxes.add(top); boxes.add(x0 + 0.03f); boxes.add(top + 0.02f)
            }
            if (row == 0) { sb.append('\n'); real.add(false); boxes.addAll(listOf(0f, 0f, 0f, 0f)) }
        }
        return PageTextLayout(sb.toString(), boxes.toFloatArray(), real.toBooleanArray())
    }

    @Test
    fun groupsCharsIntoLines() {
        val ls = TextSnap.lines(layout())
        assertEquals(2, ls.size)
        assertEquals(0.30f, ls[0].t, 1e-6f)
        assertEquals(0.38f, ls[1].b, 1e-6f)
        assertEquals(0.10f, ls[0].l, 1e-6f)
        assertEquals(0.70f, ls[0].r, 1e-5f)
    }

    @Test
    fun lineBelowTextBecomesUnderlineOfThoseChars() {
        // 在第 1 行字下面一点，从第 3 个字中间划到第 8 个字中间（y 略歪）
        val p = TextSnap.pickLine(layout(), 0.20f, 0.328f, 0.34f, 0.330f, W, H, false)!!
        assertEquals(Shapes.TEXT_UL, p.shape)
        assertEquals(0.30f, p.rect[1], 1e-6f)
        assertEquals(0.32f, p.rect[3], 1e-6f)
        // 0.20..0.34 覆盖第 4..8 字（下标 3..7），首尾字被压住 ≥40% 才算
        assertTrue(p.rect[0] <= 0.20f + 0.03f && p.rect[0] >= 0.19f)
        assertTrue(p.end - p.start >= 4)
    }

    @Test
    fun lineThroughMiddleIsStrikeAndHighlighterBecomesHighlight() {
        val strike = TextSnap.pickLine(layout(), 0.20f, 0.310f, 0.34f, 0.310f, W, H, false)!!
        assertEquals(Shapes.TEXT_STRIKE, strike.shape)
        val hl = TextSnap.pickLine(layout(), 0.20f, 0.310f, 0.34f, 0.311f, W, H, true)!!
        assertEquals(Shapes.TEXT_HL, hl.shape)
    }

    @Test
    fun farFromTextOrSlantedLinesAreNotSnapped() {
        assertNull(TextSnap.pickLine(layout(), 0.20f, 0.60f, 0.34f, 0.60f, W, H, false))   // 远离文字
        assertNull(TextSnap.pickLine(layout(), 0.20f, 0.20f, 0.34f, 0.20f, W, H, false))   // 在文字上方
        assertNull(TextSnap.pickLine(layout(), 0.20f, 0.30f, 0.34f, 0.36f, W, H, false))   // 斜线
        assertNull(TextSnap.pickLine(layout(), 0.80f, 0.33f, 0.95f, 0.33f, W, H, false))   // 在行尾以外
    }

    @Test
    fun underlineNearSecondLinePicksSecondLine() {
        val p = TextSnap.pickLine(layout(), 0.20f, 0.388f, 0.34f, 0.388f, W, H, false)!!
        assertEquals(0.36f, p.rect[1], 1e-6f)
    }

    /** 椭圆折线（归一化 x,y 交错），中心 (cx,cy)，半轴 a,b，逆时针，首尾留一点缝，略带抖动。 */
    private fun ellipseLoop(cx: Float, cy: Float, a: Float, b: Float, rnd: java.util.Random? = null, jit: Float = 0f): FloatArray {
        val n = 60
        val out = FloatArray(2 * n)
        for (i in 0 until n) {
            val t = 2 * Math.PI * i / n * 0.97
            val jx = if (rnd != null) (rnd.nextFloat() - 0.5f) * 2 * jit else 0f
            val jy = if (rnd != null) (rnd.nextFloat() - 0.5f) * 2 * jit else 0f
            out[2 * i] = cx + a * Math.cos(t).toFloat() + jx
            out[2 * i + 1] = cy + b * Math.sin(t).toFloat() + jy
        }
        return out
    }

    @Test
    fun loopAroundWordsSelectsExactlyTheCircledChars() {
        // 围着第 1 行第 5..8 个字（下标 4..7，x 0.22..0.34）的圈，比字大一圈，邻字（下标 3、8）只被蹭到边
        val e = TextSnap.pickEnclosure(layout(), ellipseLoop(0.28f, 0.31f, 0.07f, 0.028f), W, H)!!
        assertEquals(4, e.first)
        assertEquals(8, e.last)
        // 框紧贴被圈字：比字的外框只大一点点边距，绝不比手画的圈大
        assertTrue(e.rect[0] in 0.215f..0.22f)
        assertTrue(e.rect[2] in 0.34f..0.345f)
        assertTrue(e.rect[1] in 0.295f..0.30f)
        assertTrue(e.rect[3] in 0.32f..0.325f)
    }

    @Test
    fun loopOnBlankAreaIsNotSnapped() {
        assertNull(TextSnap.pickEnclosure(layout(), ellipseLoop(0.5f, 0.65f, 0.1f, 0.03f), W, H))
    }

    @Test
    fun loopThatHalfCoversNeighborsLeavesThemOut() {
        // 圈住下标 6、7（x 0.28..0.34），左右各只盖到邻字的一小半
        val e = TextSnap.pickEnclosure(layout(), ellipseLoop(0.31f, 0.31f, 0.04f, 0.026f), W, H)!!
        assertEquals(6, e.first)
        assertEquals(8, e.last)
    }

    @Test
    fun rectangleLoopAcrossTwoLinesCoversBothLines() {
        // 手画的长方形：两行的下标 5..9（x 0.25..0.40，y 0.29..0.39）
        val pts = floatArrayOf(0.243f, 0.288f, 0.407f, 0.289f, 0.406f, 0.392f, 0.244f, 0.391f, 0.243f, 0.290f)
        val e = TextSnap.pickEnclosure(layout(), pts, W, H)!!
        assertEquals(5, e.first)
        assertEquals(31, e.last)             // 第 2 行字下标从 21 起，第 10 个字是 30，end 开区间 = 31
        assertTrue(e.rect[1] < 0.30f && e.rect[3] > 0.38f)
        assertTrue(e.rect[0] in 0.245f..0.25f)
    }

    @Test
    fun ambiguousLoopOverPartOfTwoLinesIsRejected() {
        // L 形的圈：第 1 行行尾 3 个字 + 第 2 行整行。外框一合并，第 1 行前 17 个字没被圈到却都在框里 → 拒绝
        val l = floatArrayOf(0.60f, 0.295f, 0.72f, 0.295f, 0.72f, 0.385f, 0.10f, 0.385f, 0.10f, 0.355f, 0.60f, 0.355f)
        assertNull(TextSnap.pickEnclosure(layout(), l, W, H))
    }

    @Test
    fun looseHandDrawnLoopsPickExactlyTheIntendedChars() {
        // 随机的、略带抖动的手画圈：围住第 1 行里连续 1..8 个字，四周留 0.1~0.3 个字宽 / 0.2~0.5 个字高的空
        val rnd = java.util.Random(7)
        val l = layout()
        var ok = 0
        val trials = 400
        repeat(trials) {
            val k = 1 + rnd.nextInt(8)
            val s = 1 + rnd.nextInt(18 - k)
            val x0 = 0.10f + s * 0.03f
            val x1 = x0 + k * 0.03f
            val mx = (0.1f + rnd.nextFloat() * 0.2f) * 0.03f
            val my = (0.2f + rnd.nextFloat() * 0.3f) * 0.02f
            val cx = (x0 + x1) / 2f + (rnd.nextFloat() - 0.5f) * 0.004f
            val cy = 0.31f + (rnd.nextFloat() - 0.5f) * 0.004f
            val a = (x1 - x0) / 2f + mx
            val b = (0.01f + my) * 1.3f
            val e = TextSnap.pickEnclosure(l, ellipseLoop(cx, cy, a, b, rnd, 0.0012f), W, H)
            if (e != null && e.first == s && e.last == s + k) ok++
        }
        assertTrue("exact pick rate " + ok + "/" + trials, ok >= trials * 0.9)
    }

    @Test
    fun roundedRectStaysInsideItsRect() {
        val r = floatArrayOf(0.2f, 0.29f, 0.4f, 0.33f)
        val pts = TextSnap.roundedRect(r, 8f, W, H)
        assertEquals(0, pts.size % 2)
        var i = 0
        while (i < pts.size) {
            assertTrue(pts[i] >= 0.2f - 1e-5f && pts[i] <= 0.4f + 1e-5f)
            assertTrue(pts[i + 1] >= 0.29f - 1e-5f && pts[i + 1] <= 0.33f + 1e-5f)
            i += 2
        }
    }

    @Test
    fun textOfReturnsCoveredText() {
        val l = layout()
        assertEquals("字字字", TextSnap.textOf(l, 0, 3))
        assertNotNull(TextSnap.textOf(l, 0, 1))
        assertNull(TextSnap.textOf(l, 20, 21))   // 只有换行
    }

    @Test
    fun hasTextInTellsWhetherThePolygonSitsOnText() {
        assertTrue(TextSnap.hasTextIn(layout(), 0.2f, 0.29f, 0.3f, 0.33f))
        assertTrue(!TextSnap.hasTextIn(layout(), 0.2f, 0.60f, 0.3f, 0.70f))
    }

    @Test
    fun slantedUnderlineAndLowerLineStillPickTheLine() {
        // 歪约 14° 的下划线、画得比字低一截（约 0.8 个字高）
        val p = TextSnap.pickLine(layout(), 0.20f, 0.325f, 0.40f, 0.326f + 0.0f, W, H, false)
        assertNotNull(p)
        val low = TextSnap.pickLine(layout(), 0.20f, 0.336f, 0.34f, 0.336f, W, H, false)!!
        assertEquals(Shapes.TEXT_UL, low.shape)
        assertEquals(0.30f, low.rect[1], 1e-6f)
    }
}
