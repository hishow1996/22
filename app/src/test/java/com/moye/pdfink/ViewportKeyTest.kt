package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v31：铺满 / 适应宽度 / 适应屏幕三态各存各的倍率与位置。 */
class ViewportKeyTest {

    @Test
    fun legacyThreeSlotKeysAreUnchanged() {
        // 旧会话与 SessionModelsTest 的断言必须保持成立。
        assertEquals(0, PageViewportState.storageKey(0, DisplayMode.FIT))
        assertEquals(1, PageViewportState.storageKey(0, DisplayMode.WIDTH))
        assertEquals(2, PageViewportState.storageKey(0, DisplayMode.DOUBLE))
        assertEquals(9, PageViewportState.storageKey(3, DisplayMode.FIT))
    }

    @Test
    fun fillAndWidthNoLongerShareAKey() {
        // 竖屏两态的 displayMode 都是 WIDTH —— 旧公式会共用 key。
        val width = PageViewportState.storageKey(3, DisplayMode.WIDTH, false)
        val fill = PageViewportState.storageKey(3, DisplayMode.WIDTH, true)
        assertNotEquals(width, fill)
        assertEquals(width, PageViewportState.storageKey(3, DisplayMode.WIDTH))
    }

    @Test
    fun fitWidthAndFillAreAllDistinct() {
        val keys = setOf(
            PageViewportState.storageKey(7, DisplayMode.FIT, false),
            PageViewportState.storageKey(7, DisplayMode.WIDTH, false),
            PageViewportState.storageKey(7, DisplayMode.WIDTH, true)
        )
        assertEquals(3, keys.size)
    }

    @Test
    fun fillKeysNeverCollideWithLegacyKeySpace() {
        for (page in 0..2000) {
            val fill = PageViewportState.storageKey(page, DisplayMode.WIDTH, true)
            val legacyMax = PageViewportState.storageKey(2000, DisplayMode.DOUBLE, false)
            assertTrue(fill > legacyMax)
        }
        for (page in 1..2000) {
            assertEquals(
                PageViewportState.storageKey(0, DisplayMode.WIDTH, true) + page,
                PageViewportState.storageKey(page, DisplayMode.WIDTH, true)
            )
        }
    }

    @Test
    fun extremePagesAreClampedWithoutOverflow() {
        val a = PageViewportState.storageKey(Int.MAX_VALUE, DisplayMode.WIDTH, true)
        val b = PageViewportState.storageKey(Int.MAX_VALUE - 1, DisplayMode.WIDTH, true)
        assertEquals(a, b)
        assertTrue(a > 0)
        assertTrue(PageViewportState.storageKey(-9, DisplayMode.FIT, false) == 0)
    }
}
