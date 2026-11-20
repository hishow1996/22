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

    @Test
    fun loopAroundWordsBecomesTightOval() {
        // 手画的圈：围着第 1 行第 5..8 个字（x 0.22..0.34），圈比字大
        val o = TextSnap.pickOval(layout(), 0.19f, 0.285f, 0.37f, 0.335f, W, H)!!
        // 结果应该罩住这些字，且中心在字的中心
        assertTrue(o[0] <= 0.22f && o[2] >= 0.34f)
        assertTrue(o[1] <= 0.30f && o[3] >= 0.32f)
        assertEquals((0.22f + 0.34f) / 2f, (o[0] + o[2]) / 2f, 0.016f)
    }

    @Test
    fun loopOnBlankAreaOrWildlyDifferentSizeIsNotSnapped() {
        assertNull(TextSnap.pickOval(layout(), 0.4f, 0.6f, 0.6f, 0.7f, W, H))               // 没有字
        // 巨大的圈只圈住极小一部分字：尺寸差太多
        assertNull(TextSnap.pickOval(layout(), 0.05f, 0.05f, 0.95f, 0.95f, W, H))
    }

    @Test
    fun textOfReturnsCoveredText() {
        val l = layout()
        assertEquals("字字字", TextSnap.textOf(l, 0, 3))
        assertNotNull(TextSnap.textOf(l, 0, 1))
        assertNull(TextSnap.textOf(l, 20, 21))   // 只有换行
    }
}
