package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushSizesTest {

    @Test
    fun fiveStepsForBothPenAndEraser() {
        assertEquals(5, BrushSizes.COUNT)
        assertEquals(5, BrushSizes.PEN.size)
        assertEquals(5, BrushSizes.ERASER.size)
    }

    @Test
    fun widthsIncreaseMonotonically() {
        for (i in 1 until BrushSizes.COUNT) {
            assertTrue(BrushSizes.PEN[i] > BrushSizes.PEN[i - 1])
            assertTrue(BrushSizes.ERASER[i] > BrushSizes.ERASER[i - 1])
        }
    }

    @Test
    fun eraserIsAlwaysWiderThanPen() {
        for (i in 0 until BrushSizes.COUNT) {
            assertTrue(BrushSizes.ERASER[i] > BrushSizes.PEN[i] * 2f)
        }
    }

    @Test
    fun defaultIsTheMiddleStep() {
        assertEquals(BrushSizes.COUNT / 2, BrushSizes.DEFAULT)
        assertTrue(BrushSizes.DEFAULT in 0 until BrushSizes.COUNT)
    }

    @Test
    fun previewHeightGrowsAndFitsHolder() {
        for (i in 1 until BrushSizes.COUNT) {
            assertTrue(BrushSizes.previewDp(i) > BrushSizes.previewDp(i - 1))
        }
        assertTrue(BrushSizes.previewDp(BrushSizes.COUNT - 1) <= 22)
        assertEquals(15, BrushSizes.previewDp(4))
    }

    @Test
    fun legacyThreeStepIndexesRemainValid() {
        // 老用户存的是 0/1/2，扩到 5 档后仍落在合法范围
        for (i in 0..2) {
            assertTrue(i in 0 until BrushSizes.COUNT)
        }
    }
}
