package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotExportTest {

    private fun mark(shape: Int, layer: Int = 0, text: String? = null, ws: FloatArray? = null,
                     pts: FloatArray = floatArrayOf(0.1f, 0.1f, 0.5f, 0.2f)) =
        Mark(0xFF123456.toInt(), 0.004f, 200, shape, pts, ws, layer, text)

    @Test
    fun everyShapeMapsToMatchingKind() {
        val pairs = listOf(
            Shapes.FREE to AnnotInput.INK, Shapes.LINE to AnnotInput.LINE,
            Shapes.RECT to AnnotInput.RECT, Shapes.OVAL to AnnotInput.OVAL,
            Shapes.ARROW to AnnotInput.ARROW,
            Shapes.TEXT_HL to AnnotInput.HIGHLIGHT, Shapes.TEXT_UL to AnnotInput.UNDERLINE,
            Shapes.TEXT_STRIKE to AnnotInput.STRIKE
        )
        for ((shape, kind) in pairs) assertEquals(kind, AnnotExport.toInput(mark(shape))!!.kind)
    }

    @Test
    fun noteNeedsText() {
        assertNull(AnnotExport.toInput(mark(Shapes.NOTE, text = null)))
        assertNull(AnnotExport.toInput(mark(Shapes.NOTE, text = "  ")))
        assertEquals("hi", AnnotExport.toInput(mark(Shapes.NOTE, text = "hi"))!!.text)
    }

    @Test
    fun emptyAndUnknownShapesAreSkipped() {
        assertNull(AnnotExport.toInput(mark(Shapes.FREE, pts = FloatArray(0))))
        assertNull(AnnotExport.toInput(mark(99)))
    }

    @Test
    fun pressureStrokeUsesMeanWidth() {
        val m = mark(Shapes.FREE, ws = floatArrayOf(0.5f, 1.5f, 1f))
        assertEquals(0.004f, AnnotExport.meanWidth(m), 1e-6f)
        assertEquals(0.004f, AnnotExport.meanWidth(mark(Shapes.FREE)), 1e-6f)
    }

    @Test
    fun collectKeepsOnlyVisibleLayersAndNonEmptyPages() {
        val store = mapOf(
            0 to listOf(mark(Shapes.FREE, layer = 0), mark(Shapes.FREE, layer = 1)),
            2 to listOf(mark(Shapes.TEXT_HL, layer = 1)),
            3 to listOf(mark(Shapes.NOTE, text = null))
        )
        val out = AnnotExport.collect(5, setOf(0)) { store[it] ?: emptyList() }
        assertEquals(setOf(0), out.keys)
        assertEquals(1, out[0]!!.size)
        val both = AnnotExport.collect(5, setOf(0, 1)) { store[it] ?: emptyList() }
        assertEquals(setOf(0, 2), both.keys)
        assertTrue(both[2]!!.single().kind == AnnotInput.HIGHLIGHT)
    }

    @Test
    fun stampsBecomeInkOrNoteAndCoversAreSkipped() {
        fun stamp(shape: Int) = Mark(Stamps.color(shape), 0.006f, 255, shape,
            floatArrayOf(0.4f, 0.4f, 0.5f, 0.45f))
        val ok = AnnotExport.toInput(stamp(Shapes.STAMP_OK))!!
        assertEquals(AnnotInput.INK, ok.kind); assertEquals(6, ok.pts.size)
        val bad = AnnotExport.toInput(stamp(Shapes.STAMP_BAD))!!
        assertEquals(AnnotInput.INK, bad.kind); assertEquals(12, bad.pts.size)
        val q = AnnotExport.toInput(stamp(Shapes.STAMP_Q))!!
        assertEquals(AnnotInput.NOTE, q.kind); assertEquals("?", q.text); assertEquals(2, q.pts.size)
        // 折线都落在章的外接盒内
        for (i in 0 until ok.pts.size step 2) {
            assertTrue(ok.pts[i] in 0.4f..0.5f && ok.pts[i + 1] in 0.4f..0.45f)
        }
        val cover = Mark(CoverGeometry.COLOR, 0.004f, 255, Shapes.COVER, floatArrayOf(0f, 0f, 1f, 1f))
        assertNull(AnnotExport.toInput(cover))
    }

    @Test
    fun textBoxExportsAsFreeTextWhenOutlineKnownElseAsNote() {
        val m = Mark(0xFF123456.toInt(), 0.026f, 255, Shapes.TEXTBOX,
            floatArrayOf(0.1f, 0.2f, 0.7f, 0.3f), TextBoxMath.bgWs(true), 0, "你好")
        // 没有轮廓：退回便签，位置 = 框左上角
        val n = AnnotExport.toInput(m)!!
        assertEquals(AnnotInput.NOTE, n.kind)
        assertEquals(0.1f, n.pts[0], 0f)
        assertEquals("你好", n.text)
        // 有轮廓：FreeText，外框 / 轮廓 / 底色带上
        val o = TextBoxOutline(floatArrayOf(0.1f, 0.2f, 0.7f, 0.25f), listOf(floatArrayOf(0.1f, 0.2f, 0.2f, 0.2f, 0.2f, 0.3f)))
        val f = AnnotExport.toInput(m) { o }!!
        assertEquals(AnnotInput.FREETEXT, f.kind)
        assertTrue(f.fill)
        assertEquals(0.25f, f.pts[3], 0f)
        assertEquals(1, f.contours!!.size)
        // 空文字不导出
        assertNull(AnnotExport.toInput(Mark(0, 0.026f, 255, Shapes.TEXTBOX, floatArrayOf(0f, 0f, 0.5f, 0.1f), null, 0, " ")))
    }
}
