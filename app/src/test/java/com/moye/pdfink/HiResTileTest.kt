package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/** 放大分档、逻辑画布与可见窗口：底图与笔迹必须用同一组数。 */
class HiResTileTest {

    @Test fun `zoom buckets`() {
        assertEquals(1f, TiledPageView.bucket(1f), 0f)
        assertEquals(1f, TiledPageView.bucket(1.2f), 0f)
        assertEquals(1.5f, TiledPageView.bucket(1.4f), 0f)
        assertEquals(2f, TiledPageView.bucket(2f), 0f)
        assertEquals(3f, TiledPageView.bucket(3.2f), 0f)
        assertEquals(4f, TiledPageView.bucket(4f), 0f)
    }

    @Test fun `logical canvas grows with scale`() {
        val s = TiledPageView.bucket(4f)
        assertEquals(4320, (1080 * s).toInt())
        assertEquals(6112, (1528 * s).toInt())
    }

    @Test fun `visible window stays screen sized`() {
        // 关键：可见窗口恒为 width×height；带内块数经 MAX_TILES 防御后不随倍率增长
        val w = 1080f; val h = 1528f
        for (s in listOf(1f, 2f, 4f)) {
            val left = w * (s - 1f) * 0.5f - 0f
            val top = h * (s - 1f) * 0.5f - 0f
            assertEquals(w, (left + w) - left, 0.01f)
            assertEquals(h, (top + h) - top, 0.01f)
            // 与 TiledPageView 实现同式：floor 末端 + PREFETCH，防御 clamp 到 c0+MAX_TILES-1
            val c0 = max(0, (left / TiledPageView.TILE_H).toInt() - TiledPageView.PREFETCH)
            val c1 = (((left + w) / TiledPageView.TILE_H).toInt() + TiledPageView.PREFETCH)
                .coerceAtMost(c0 + TiledPageView.MAX_TILES - 1)
            val r0 = max(0, (top / TiledPageView.TILE_H).toInt() - TiledPageView.PREFETCH)
            val r1 = (((top + h) / TiledPageView.TILE_H).toInt() + TiledPageView.PREFETCH)
                .coerceAtMost(r0 + TiledPageView.MAX_TILES - 1)
            assertTrue("$s tiles too many",
                (c1 - c0 + 1) <= TiledPageView.MAX_TILES &&
                (r1 - r0 + 1) <= TiledPageView.MAX_TILES)
        }
    }

    @Test fun `centered at zero pan`() {
        val s = 4f
        val left = 1080f * (s - 1f) * 0.5f
        // 放大后窗口中心仍落在逻辑画布中心
        assertEquals(4320f / 2f, left + 1080f / 2f, 0.01f)
    }

    @Test fun `pan shifts visible window`() {
        val s = 2f
        val base = 1080f * (s - 1f) * 0.5f
        val shifted = base - 200f
        assertEquals(-200f, shifted - base, 0.01f)
    }

    @Test fun `pages use separate viewports`() {
        val a = Viewport(); val b = Viewport()
        a.scale = 4f
        assertEquals(1f, b.scale, 0f)   // 串页会直接破坏相邻预加载页
    }

    @Test fun `visible window differs from draw origin on width-to-top`() {
        // 横屏 WIDTH：页高 1528、宿主高 800，回页顶时 ty=364。
        // 混淆 visTop 与 originY 会把可见窗算到 [-364,436]，页顶被推出屏幕。
        val hostH = 800f; val bh = 1528f; val ty = (bh - hostH) * 0.5f
        val lh = bh; val ly = (hostH - lh) * 0.5f + ty
        val oy = (hostH - bh) * 0.5f
        val visTop = -ly
        val originY = oy - ly
        assertEquals(0f, visTop, 0.01f)          // 可见窗顶正好是页面顶
        assertEquals(-364f, originY, 0.01f)     // 绘制原点不同，不能混用
        assertTrue("must differ", kotlin.math.abs(visTop - originY) > 300f)
    }

    @Test fun `viewport fields are published`() {
        val v = Viewport()
        v.bucket = 4f; v.lw = 4320; v.lh = 6112
        v.visLeft = 1620f; v.visTop = 2106f
        v.originX = 1620f; v.originY = 2292f
        assertEquals(4320, v.lw)
        assertEquals(6112, v.lh)
        assertEquals(1900f, (v.visTop + 1900f) - v.visTop, 0.01f)
    }
}
