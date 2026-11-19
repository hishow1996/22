package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotSummaryTest {

    private fun m(shape: Int, layer: Int = 0, text: String? = null,
                  pts: FloatArray = floatArrayOf(0.1f, 0.1f, 0.4f, 0.2f)) =
        Mark(0xFF000000.toInt(), 0.004f, 255, shape, pts, null, layer, text)

    @Test
    fun snippetCollapsesWhitespaceAndTruncates() {
        assertEquals("", AnnotSummary.snippet(null))
        assertEquals("", AnnotSummary.snippet("  \n "))
        assertEquals("a b c", AnnotSummary.snippet(" a \n b\t c "))
        val long = "x".repeat(100)
        val s = AnnotSummary.snippet(long, 10)
        assertEquals("x".repeat(10) + "…", s)
    }

    @Test
    fun textMarksAndNotesGetOwnRowsStrokesMergePerPage() {
        val store = mapOf(
            1 to listOf(
                m(Shapes.TEXT_HL, text = "重点句子"),
                m(Shapes.FREE), m(Shapes.FREE), m(Shapes.RECT),
                m(Shapes.NOTE, text = "回头再看"),
                m(Shapes.TEXT_STRIKE, text = null)
            ),
            3 to listOf(m(Shapes.ARROW))
        )
        val out = AnnotSummary.build(5, setOf(0)) { store[it] ?: emptyList() }
        assertEquals(listOf(1, 1, 1, 1, 3), out.map { it.page })
        assertEquals(
            listOf(AnnotSummary.HIGHLIGHT, AnnotSummary.NOTE, AnnotSummary.STRIKE,
                AnnotSummary.INK, AnnotSummary.INK),
            out.map { it.kind }
        )
        assertEquals("重点句子", out[0].snippet)
        assertEquals("回头再看", out[1].snippet)
        assertEquals(2, out[3].strokes); assertEquals(1, out[3].shapes)
        assertEquals(0, out[4].strokes); assertEquals(1, out[4].shapes)
    }

    @Test
    fun hiddenLayersAndEmptyNotesAreIgnored() {
        val store = mapOf(
            0 to listOf(m(Shapes.TEXT_HL, layer = 1, text = "隐藏层"), m(Shapes.NOTE, text = " "))
        )
        assertTrue(AnnotSummary.build(2, setOf(0)) { store[it] ?: emptyList() }.isEmpty())
        val shown = AnnotSummary.build(2, setOf(0, 1)) { store[it] ?: emptyList() }
        assertEquals(1, shown.size)
        assertEquals("隐藏层", shown[0].snippet)
    }

    @Test
    fun stampsMergePerPageAndCoversAreIgnored() {
        val store = mapOf(
            0 to listOf(m(Shapes.STAMP_OK), m(Shapes.STAMP_OK), m(Shapes.STAMP_BAD), m(Shapes.COVER)),
            2 to listOf(m(Shapes.STAMP_Q), m(Shapes.STAMP_BAD)),
            3 to listOf(m(Shapes.COVER)),
            4 to listOf(m(Shapes.STAMP_OK, layer = 1))
        )
        val out = AnnotSummary.build(5, setOf(0)) { store[it] ?: emptyList() }
        assertEquals(listOf(0, 2), out.map { it.page })
        assertTrue(out.all { it.kind == AnnotSummary.STAMP })
        assertEquals(2, out[0].ok); assertEquals(1, out[0].bad); assertEquals(0, out[0].q)
        assertEquals(1, out[1].q); assertEquals(1, out[1].bad)
        assertEquals(listOf(2, 2, 1), AnnotSummary.stampTotals(out).toList())
        assertEquals(listOf(0, 2), AnnotSummary.badPages(out))
    }

    @Test
    fun capStopsEarly() {
        var loaded = 0
        val out = AnnotSummary.build(1000, setOf(0), maxEntries = 3) {
            loaded++
            listOf(m(Shapes.TEXT_HL, text = "a"))
        }
        assertTrue(out.size in 3..3)
        assertTrue(loaded <= 3)
    }
}
