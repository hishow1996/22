package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedoPageTest {

    private fun m(shape: Int, layer: Int = 0) =
        Mark(0xFF000000.toInt(), 0.004f, 255, shape, floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f), null, layer)

    @Test
    fun answersAreRemovedMarkupCoversAndNotesStay() {
        val marks = listOf(
            m(Shapes.TEXT_HL), m(Shapes.FREE), m(Shapes.LINE), m(Shapes.RECT), m(Shapes.OVAL),
            m(Shapes.ARROW), m(Shapes.STAMP_OK), m(Shapes.STAMP_BAD), m(Shapes.STAMP_Q),
            m(Shapes.COVER), m(Shapes.NOTE), m(Shapes.TEXT_UL), m(Shapes.TEXT_STRIKE)
        )
        val kept = RedoPage.keep(marks) { true }
        assertEquals(
            listOf(Shapes.TEXT_HL, Shapes.COVER, Shapes.NOTE, Shapes.TEXT_UL, Shapes.TEXT_STRIKE),
            kept.map { it.shape }
        )
    }

    @Test
    fun hiddenLayerAnswersAreLeftAlone() {
        val marks = listOf(m(Shapes.FREE, layer = 0), m(Shapes.FREE, layer = 1), m(Shapes.STAMP_OK, layer = 1))
        val kept = RedoPage.keep(marks) { it.layer == 0 }
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.layer == 1 })
    }

    @Test
    fun nothingToClearKeepsEverything() {
        val marks = listOf(m(Shapes.TEXT_HL), m(Shapes.COVER))
        assertEquals(marks, RedoPage.keep(marks) { true })
        assertFalse(RedoPage.isAnswer(Shapes.NOTE))
        assertTrue(RedoPage.isAnswer(Shapes.FREE))
    }
}
