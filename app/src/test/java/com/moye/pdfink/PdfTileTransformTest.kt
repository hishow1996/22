package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTileTransformTest {

    private fun point(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[1] * y + m[2]) to
            (m[3] * x + m[4] * y + m[5])

    @Test
    fun rotationZeroKeepsPageCornerLocations() {
        val m = PdfTileTransform.matrixValues(100f, 200f, 400, 40, 60, 0)
        assertEquals( -40f, point(m, 0f, 0f).first, 0.0001f)
        assertEquals( -60f, point(m, 0f, 0f).second, 0.0001f)
        assertEquals(360f, point(m, 100f, 0f).first, 0.0001f)
        assertEquals(740f, point(m, 100f, 200f).second, 0.0001f)
    }

    @Test
    fun rotationNinetyMapsAllCornersBeforeTileOffset() {
        val m = PdfTileTransform.matrixValues(100f, 200f, 400, 40, 60, 90)
        assertEquals(360f, point(m, 0f, 0f).first, 0.0001f)
        assertEquals(-60f, point(m, 0f, 0f).second, 0.0001f)
        assertEquals(-40f, point(m, 0f, 200f).first, 0.0001f)
        assertEquals(140f, point(m, 100f, 200f).second, 0.0001f)
    }

    @Test
    fun rotationOneEightyMapsPageToOppositeCorner() {
        val m = PdfTileTransform.matrixValues(100f, 200f, 400, 40, 60, 180)
        assertEquals(360f, point(m, 0f, 0f).first, 0.0001f)
        assertEquals(740f, point(m, 0f, 0f).second, 0.0001f)
        assertEquals(-40f, point(m, 100f, 200f).first, 0.0001f)
        assertEquals(-60f, point(m, 100f, 200f).second, 0.0001f)
    }

    @Test
    fun rotationTwoSeventyMapsPageCorners() {
        val m = PdfTileTransform.matrixValues(100f, 200f, 400, 40, 60, 270)
        assertEquals(-40f, point(m, 0f, 0f).first, 0.0001f)
        assertEquals(340f, point(m, 0f, 0f).second, 0.0001f)
        assertEquals(360f, point(m, 100f, 200f).first, 0.0001f)
        assertEquals(-60f, point(m, 100f, 200f).second, 0.0001f)
    }

    @Test
    fun nonRightAngleStoredRotationIsNormalizedSafely() {
        assertEquals(0, PdfTileTransform.normalizeRotation(359))
        assertEquals(90, PdfTileTransform.normalizeRotation(45))
        assertEquals(180, PdfTileTransform.normalizeRotation(179))
        assertEquals(270, PdfTileTransform.normalizeRotation(270))
        assertEquals(0, PdfTileTransform.normalizeRotation(360))
    }

    @Test
    fun visibleCurrentTilesAlwaysHaveHighestPriority() {
        assertEquals(0, PdfTilePriority.value(currentPage = true, visibleTile = true))
        assertEquals(1, PdfTilePriority.value(currentPage = true, visibleTile = false))
        assertEquals(2, PdfTilePriority.value(currentPage = false, visibleTile = true))
        assertEquals(3, PdfTilePriority.value(currentPage = false, visibleTile = false))
    }
}
