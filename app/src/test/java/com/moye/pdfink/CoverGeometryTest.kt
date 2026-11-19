package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGeometryTest {

    private fun cover(x0: Float, y0: Float, x1: Float, y1: Float, layer: Int = 0, revealed: Boolean = false) =
        Mark(0xFF7C8DB0.toInt(), 0.004f, 255, Shapes.COVER, floatArrayOf(x0, y0, x1, y1), null, layer)
            .also { it.revealed = revealed }

    @Test
    fun rectNormalizesCornerOrder() {
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f),
            CoverGeometry.rect(floatArrayOf(0.5f, 0.6f, 0.1f, 0.2f))!!, 0f)
        assertNull(CoverGeometry.rect(floatArrayOf(0.1f, 0.2f)))
    }

    @Test
    fun containsUsesNormalizedRect() {
        val p = floatArrayOf(0.2f, 0.2f, 0.6f, 0.4f)
        assertTrue(CoverGeometry.contains(p, 0.3f, 0.3f))
        assertTrue(CoverGeometry.contains(p, 0.2f, 0.4f))
        assertFalse(CoverGeometry.contains(p, 0.7f, 0.3f))
        assertFalse(CoverGeometry.contains(floatArrayOf(0f, 0f), 0f, 0f))
    }

    @Test
    fun badgeSitsInsideTopRightAndShrinksToCenterOnTinyCover() {
        val c = CoverGeometry.badgeCenter(floatArrayOf(0.2f, 0.2f, 0.8f, 0.6f), 1000f, 1000f, 20f)!!
        assertEquals(780f, c[0], 0.01f); assertEquals(220f, c[1], 0.01f)
        val tiny = CoverGeometry.badgeCenter(floatArrayOf(0.5f, 0.5f, 0.51f, 0.51f), 1000f, 1000f, 20f)!!
        assertEquals(505f, tiny[0], 0.01f); assertEquals(505f, tiny[1], 0.01f)
    }

    @Test
    fun hiddenCoverHitsAnywhereInsideRevealedOnlyOnBadge() {
        val hidden = cover(0.2f, 0.2f, 0.8f, 0.6f)
        assertEquals(0, CoverGeometry.hitIndex(listOf(hidden), 1000f, 1000f, 500f, 400f, 20f, 24f))
        assertEquals(-1, CoverGeometry.hitIndex(listOf(hidden), 1000f, 1000f, 900f, 900f, 20f, 24f))
        val open = cover(0.2f, 0.2f, 0.8f, 0.6f, revealed = true)
        assertEquals(-1, CoverGeometry.hitIndex(listOf(open), 1000f, 1000f, 500f, 400f, 20f, 24f))
        assertEquals(0, CoverGeometry.hitIndex(listOf(open), 1000f, 1000f, 775f, 225f, 20f, 24f))
    }

    @Test
    fun topmostCoverWinsAndOtherShapesAndHiddenLayersAreSkipped() {
        val bottom = cover(0f, 0f, 1f, 1f)
        val top = cover(0.4f, 0.4f, 0.6f, 0.6f)
        val rect = Mark(0xFF000000.toInt(), 0.004f, 255, Shapes.RECT, floatArrayOf(0f, 0f, 1f, 1f))
        val marks = listOf(bottom, top, rect)
        assertEquals(1, CoverGeometry.hitIndex(marks, 100f, 100f, 50f, 50f, 5f, 8f))
        assertEquals(0, CoverGeometry.hitIndex(marks, 100f, 100f, 10f, 10f, 5f, 8f))
        // 顶上那块在隐藏图层：落到下面那块
        val hiddenTop = cover(0.4f, 0.4f, 0.6f, 0.6f, layer = 1)
        assertEquals(0, CoverGeometry.hitIndex(listOf(bottom, hiddenTop), 100f, 100f, 50f, 50f, 5f, 8f) { it.layer == 0 })
    }

    @Test
    fun revealedFlagIsNotPartOfEqualityOrJson() {
        val a = cover(0f, 0f, 1f, 1f)
        val b = a.copy().also { it.revealed = true }   // 数组字段按引用比较，所以用 copy 共享同一份 pts
        assertEquals(a, b)
        assertFalse(b.toJson().has("revealed"))
        assertFalse(b.copyWith(pts = floatArrayOf(0f, 0f, 1f, 1f)).revealed)
    }
}
