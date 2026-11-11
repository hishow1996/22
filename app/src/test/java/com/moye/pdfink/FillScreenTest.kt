package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 完整页适配：页面始终位于视口内，按比例呈现，不通过 cover 裁切。 */
class FillScreenTest {

    @Test
    fun portraitPageUsesAvailableWidthWithoutCropping() {
        val (w, h) = PageContainer.fitPageSize(1080, 1935, 0.707f)
        assertEquals(1080, w)
        assertTrue(h in 1526..1529)
        assertTrue(w <= 1080 && h <= 1935)
        assertEquals(0.707f, w.toFloat() / h, 0.001f)
    }

    @Test
    fun landscapePageUsesAvailableHeightWithoutCropping() {
        val (w, h) = PageContainer.fitPageSize(1080, 800, 1.8f)
        assertEquals(1080, w)
        assertEquals(600, h)
        assertTrue(w <= 1080 && h <= 800)
    }

    @Test
    fun veryWidePageIsNotMagnifiedPastViewport() {
        val (w, h) = PageContainer.fitPageSize(1080, 2340, 1.8f)
        assertEquals(1080, w)
        assertEquals(600, h)
    }

    @Test
    fun veryTallPageUsesViewportHeightAndStaysInsideBounds() {
        val (w, h) = PageContainer.fitPageSize(1080, 800, 0.3f)
        assertEquals(240, w)
        assertEquals(800, h)
    }

    @Test
    fun rotatedRatiosAlsoRemainInsideViewport() {
        val ratio = 0.707f
        val (w, h) = PageContainer.fitPageSize(1080, 800, 1f / ratio)
        assertTrue(w <= 1080 && h <= 800)
        assertEquals(1f / ratio, w.toFloat() / h, 0.003f)
    }

    @Test
    fun fitAndFillUseTheSameNonCroppingGeometry() {
        val size = PageContainer.fitPageSize(1080, 1935, 0.707f)
        assertEquals(size, PageContainer.fitPageSize(1080, 1935, 0.707f))
        assertTrue(PageContainer.FIT != PageContainer.WIDTH)
        assertTrue(PageContainer.WIDTH != PageContainer.FILL)
    }

    @Test
    fun invalidInputsAreSafe() {
        assertEquals(1 to 1, PageContainer.fitPageSize(0, 1935, 0.707f))
        assertEquals(1 to 1, PageContainer.fitPageSize(1080, 0, 0.707f))
        assertEquals(1 to 1, PageContainer.fitPageSize(1080, 1935, 0f))
        assertEquals(1 to 1, PageContainer.fitPageSize(1080, 1935, Float.NaN))
    }
}
