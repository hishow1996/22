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
}
