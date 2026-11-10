package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 放大分档、逻辑画布与可见窗口：底图与笔迹必须使用同一组坐标。 */
class HiResTileTest {

    @Test fun `zoom buckets include zoom out`() {
        assertEquals(0.5f, TiledPageView.bucket(0.5f), 0f)
        assertEquals(0.67f, TiledPageView.bucket(0.7f), 0f)
        assertEquals(0.8f, TiledPageView.bucket(0.85f), 0f)
        assertEquals(1f, TiledPageView.bucket(1f), 0f)
        assertEquals(1f, TiledPageView.bucket(1.2f), 0f)
        assertEquals(1.5f, TiledPageView.bucket(1.4f), 0f)
        assertEquals(2f, TiledPageView.bucket(2f), 0f)
        assertEquals(3f, TiledPageView.bucket(3.2f), 0f)
        assertEquals(4f, TiledPageView.bucket(4f), 0f)
    }

    @Test fun `logical canvas follows bucket at reduced scale`() {
        assertEquals(540, (1080 * TiledPageView.bucket(0.5f)).toInt())
        assertEquals(4320, (1080 * TiledPageView.bucket(4f)).toInt())
        assertEquals(6112, (1528 * TiledPageView.bucket(4f)).toInt())
    }

    @Test fun `visible range covers the complete landscape viewport`() {
        val cols = TiledPageView.tileRange(7200, 0f, 3200)
        assertEquals(10, cols.count())
        assertTrue(cols.contains(7))
        assertTrue(cols.contains(8))
        val rows = TiledPageView.tileRange(5200, 1600f, 1600)
        assertTrue(rows.contains(4))
        assertTrue(rows.contains(5))
    }

    @Test fun `visible range clamps at page edges`() {
        assertEquals(0..3, TiledPageView.tileRange(3055, 0f, 1080))
        assertEquals(5..7, TiledPageView.tileRange(3055, 2600f, 455))
    }

    @Test fun `viewport instances remain page specific`() {
        val a = Viewport()
        val b = Viewport()
        a.scale = 4f
        assertEquals(1f, b.scale, 0f)
    }
}
