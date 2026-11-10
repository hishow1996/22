package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryCoreTest {

    private fun stroke(vararg xy: Float, layer: Int = 0) =
        Mark(0xFF222226.toInt(), 0.01f, 255, Shapes.FREE, xy, null, layer)

    @Test
    fun cropSplitsStrokeIntoTwoPieces() {
        val m = stroke(0f, 0.5f, 0.25f, 0.5f, 0.5f, 0.5f, 0.75f, 0.5f, 1f, 0.5f)
        // 擦除竖线 x=0.5，页面 1000x1000，半径 12px
        val e = floatArrayOf(500f, 0f, 500f, 1000f)
        val out = GeometryCore.crop(listOf(m), e, 12f, 1000f, 1000f) { true }
        assertEquals(2, out.size)
        assertTrue(out[0].pts[0] < 0.5f)
        assertTrue(out[1].pts[out[1].pts.size - 2] > 0.5f)
    }

    @Test
    fun untouchedStrokeKeepsOriginalObject() {
        val m = stroke(0f, 0.1f, 0.2f, 0.1f, 0.4f, 0.1f)
        val e = floatArrayOf(900f, 900f, 950f, 950f)
        val out = GeometryCore.crop(listOf(m), e, 5f, 1000f, 1000f) { true }
        assertEquals(1, out.size)
        assertTrue(out[0] === m)
    }

    @Test
    fun hiddenLayerIsNeverErased() {
        val m = stroke(0f, 0.5f, 1f, 0.5f, layer = Layers.CORRECTION)
        val e = floatArrayOf(500f, 0f, 500f, 1000f)
        val out = GeometryCore.crop(listOf(m), e, 20f, 1000f, 1000f) {
            it == Layers.ANSWER
        }
        assertEquals(1, out.size)
        assertTrue(out[0] === m)
    }

    @Test
    fun shapeHitIsRemovedAsWhole() {
        val m = Mark(0, 0.01f, 255, Shapes.RECT,
            floatArrayOf(0.1f, 0.1f, 0.6f, 0.6f))
        val e = floatArrayOf(300f, 300f, 320f, 320f)
        val out = GeometryCore.crop(listOf(m), e, 10f, 1000f, 1000f) { true }
        assertEquals(0, out.size)
    }

    @Test
    fun polygonContainsAndExcludes() {
        val poly = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertTrue(GeometryCore.contains(poly, 0.5f, 0.5f))
        assertFalse(GeometryCore.contains(poly, 1.2f, 0.5f))
    }

    @Test
    fun transformKeepsCenterAndScales() {
        val m = stroke(0.4f, 0.4f, 0.6f, 0.6f)
        val t = GeometryCore.transform(m, 0.5f, 0.5f, 0f, 0f, 2f)
        assertArrayEquals(floatArrayOf(0.3f, 0.3f, 0.7f, 0.7f), t.pts, 0.0001f)
        assertEquals(0.02f, t.width, 0.0001f)
    }

    @Test
    fun eraseBoundsAreClamped() {
        val b = GeometryCore.clampRect(
            GeometryCore.eraseBounds(floatArrayOf(10f, 10f, 20f, 20f), 50f),
            100f, 100f
        )
        assertArrayEquals(floatArrayOf(0f, 0f, 70f, 70f), b, 0.0001f)
    }
}
