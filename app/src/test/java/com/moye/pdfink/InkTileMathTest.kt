package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTileMathTest {

    @Test
    fun countRoundsUp() {
        assertEquals(0, InkTileMath.count(0))
        assertEquals(1, InkTileMath.count(1))
        assertEquals(1, InkTileMath.count(256))
        assertEquals(2, InkTileMath.count(257))
        assertEquals(9, InkTileMath.count(2300))
    }

    @Test
    fun edgeTileIsSmaller() {
        // 2300 = 8 * 256 + 252
        assertEquals(256, InkTileMath.tileSize(0, 2300))
        assertEquals(256, InkTileMath.tileSize(7, 2300))
        assertEquals(252, InkTileMath.tileSize(8, 2300))
    }

    @Test
    fun rangeCoversRectAndClamps() {
        assertEquals(0..0, InkTileMath.range(10f, 100f, 9))
        assertEquals(0..1, InkTileMath.range(200f, 300f, 9))
        // 超出页面的部分被钳住
        assertEquals(0..8, InkTileMath.range(-500f, 99999f, 9))
        assertEquals(7..8, InkTileMath.range(1800f, 99999f, 9))
    }

    @Test
    fun rangeIsEmptyWhenNoOverlap() {
        assertTrue(InkTileMath.range(-100f, -1f, 9).isEmpty())
        assertTrue(InkTileMath.range(5000f, 6000f, 9).isEmpty())
        assertTrue(InkTileMath.range(10f, 5f, 9).isEmpty())
        assertTrue(InkTileMath.range(0f, 10f, 0).isEmpty())
        assertTrue(InkTileMath.range(Float.NaN, 10f, 9).isEmpty())
    }

    @Test
    fun rangeHandlesInfinities() {
        assertEquals(0..8, InkTileMath.range(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 9))
    }
}
