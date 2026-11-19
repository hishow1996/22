package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoupeMathTest {

    @Test
    fun windowHeightKeepsStripAspect() {
        // 页 1000x1400（比 0.714），条 1000x250，窗宽占 0.4：页上窗 400 宽、100 高 → 100/1400
        val wh = LoupeMath.winH(0.4f, 1000f / 1400f, 1000f, 250f)
        assertEquals(100f / 1400f, wh, 1e-4f)
        assertEquals(0f, LoupeMath.winH(0.4f, 0.7f, 0f, 250f), 0f)
        assertEquals(1f, LoupeMath.winH(0.6f, 3f, 100f, 1000f), 0f)
    }

    @Test
    fun stripPointsMapProportionallyBackToPage() {
        val ww = 0.4f; val wh = 0.07f
        assertEquals(0.1f, LoupeMath.toPageX(0f, 1000f, 0.1f, ww), 1e-6f)
        assertEquals(0.3f, LoupeMath.toPageX(500f, 1000f, 0.1f, ww), 1e-6f)
        assertEquals(0.5f, LoupeMath.toPageX(1000f, 1000f, 0.1f, ww), 1e-6f)
        assertEquals(0.335f, LoupeMath.toPageY(125f, 250f, 0.3f, wh), 1e-6f)
    }

    @Test
    fun advancesOnlyWhenWritingNearRightEdge() {
        assertNull(LoupeMath.advancedX(0.1f, 0.4f, 0.3f))          // 在 50% 处
        val nx = LoupeMath.advancedX(0.1f, 0.4f, 0.1f + 0.4f * 0.8f)!! // 在 80% 处
        assertEquals(0.3f, nx, 1e-6f)
        // 已经靠在页右边就不动
        assertNull(LoupeMath.advancedX(0.6f, 0.4f, 0.98f))
        // 挪动夹在页内
        assertEquals(0.6f, LoupeMath.advancedX(0.5f, 0.4f, 0.95f)!!, 1e-6f)
    }

    @Test
    fun centerOnAndNextLineStayInsidePage() {
        val c = LoupeMath.centerOn(0.99f, 0.01f, 0.4f, 0.1f)
        assertEquals(0.6f, c.first, 1e-6f)
        assertEquals(0f, c.second, 1e-6f)
        val n = LoupeMath.nextLine(0.05f, 0.2f, 0.4f, 0.1f)
        assertEquals(0.05f, n.first, 1e-6f)
        assertEquals(0.26f, n.second, 1e-6f)
        val end = LoupeMath.nextLine(0.05f, 0.95f, 0.4f, 0.1f)
        assertEquals(0.9f, end.second, 1e-6f)
    }

    @Test
    fun coveredChecksContainment() {
        assertTrue(LoupeMath.covered(100f, 50f, 300f, 100f, 0f, 0f, 1000f, 500f))
        assertFalse(LoupeMath.covered(800f, 50f, 300f, 100f, 0f, 0f, 1000f, 500f))
        assertFalse(LoupeMath.covered(100f, 450f, 300f, 100f, 0f, 0f, 1000f, 500f))
        assertNotNull(LoupeMath.nextLine(0f, 0f, 0.4f, 0.1f))
    }
}
