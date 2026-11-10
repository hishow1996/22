package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InkPaletteTest {

    @Test
    fun nightInvertsDarkInkIntoVisibleLight() {
        val black = (0xFF shl 24) or (0x22 shl 16) or (0x22 shl 8) or 0x26
        val mapped = InkPalette.mapColor(black, InkPalette.NIGHT)
        val r = (mapped ushr 16) and 0xff
        val g = (mapped ushr 8) and 0xff
        val b = mapped and 0xff
        assertEquals(true, r > 170 && g > 170 && b > 160)
        assertEquals(0xFF, (mapped ushr 24) and 0xff)
    }

    @Test
    fun whiteAndWarmLeaveInkUntouched() {
        val c = (0xFF shl 24) or (0x2F shl 16) or (0x6F shl 8) or 0xED
        assertEquals(c, InkPalette.mapColor(c, InkPalette.WHITE))
        assertEquals(c, InkPalette.mapColor(c, InkPalette.WARM))
    }

    @Test
    fun lightInkBecomesDarkOnNightPaper() {
        val white = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        val mapped = InkPalette.mapColor(white, InkPalette.NIGHT)
        val r = (mapped ushr 16) and 0xff
        assertEquals(true, r < 10)
        assertNotEquals(white, mapped)
    }

    @Test
    fun rotate90MapsNormalizedPoint() {
        val pts = floatArrayOf(0.2f, 0.3f, 0.8f, 0.1f)
        val r = InkPalette.rotatePts(pts, 90)
        assertArrayEquals(floatArrayOf(0.7f, 0.2f, 0.9f, 0.8f), r, 0.0001f)
    }

    @Test
    fun fourRotationsReturnToStart() {
        val pts = floatArrayOf(0.13f, 0.77f, 0.42f, 0.05f)
        var r = pts
        repeat(4) { r = InkPalette.rotatePts(r, 90) }
        assertArrayEquals(pts, r, 0.0001f)
    }

    @Test
    fun negativeDegreesAreNormalized() {
        val pts = floatArrayOf(0.2f, 0.3f)
        assertArrayEquals(
            InkPalette.rotatePts(pts, 90),
            InkPalette.rotatePts(pts, -270),
            0.0001f
        )
    }
}
