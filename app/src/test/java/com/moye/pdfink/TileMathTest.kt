package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

/** 条带索引：只覆盖可见 clip + 上下各一块预取，末块不越界。 */
class TileMathTest {

    private fun range(targetH: Int, clipTop: Int, clipBottom: Int): IntRange {
        val count = (targetH + TiledPageView.TILE_H - 1) / TiledPageView.TILE_H
        val first = (clipTop / TiledPageView.TILE_H - TiledPageView.PREFETCH)
            .coerceAtLeast(0)
        val last = ((clipBottom + TiledPageView.TILE_H - 1) /
            TiledPageView.TILE_H + TiledPageView.PREFETCH)
            .coerceAtMost(count - 1)
        return first..last
    }

    @Test fun `visible band only`() {
        // 3055px 页，可见 0..1080 → 块 0,1,2 + 预取 3
        assertEquals(0..3, range(3055, 0, 1080))
    }

    @Test fun `middle band`() {
        assertEquals(2..6, range(3055, 1000, 2080))
    }

    @Test fun `last band clamped`() {
        assertEquals(5..7, range(3055, 2600, 3055))
    }

    @Test fun `never renders whole page`() {
        assertTrue(range(3055, 0, 1080).count() < 8)
    }

    private fun assertTrue(b: Boolean) { org.junit.Assert.assertTrue(b) }
}
