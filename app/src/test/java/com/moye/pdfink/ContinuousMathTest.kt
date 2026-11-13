package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousMathTest {

    @Test
    fun a4PageFillsWidthAndKeepsRatio() {
        // 1080 宽、两侧各留 18px → 页宽 1044，A4 比例 0.707 → 高约 1477
        val h = ContinuousMath.itemHeight(1080, 18, 0.707f)
        assertEquals(1477, h)
    }

    @Test
    fun heightMatchesPageContainerRounding() {
        val availW = 1080 - 36
        val ratio = 0.7071f
        val expected = PageContainer.fitPageSize(availW, 100000, ratio).second
        assertEquals(expected, ContinuousMath.itemHeight(1080, 18, ratio))
    }

    @Test
    fun invalidInputsFallBack() {
        assertEquals(-1, ContinuousMath.itemHeight(0, 18, 0.7f))
        assertEquals(-1, ContinuousMath.itemHeight(1080, 18, 0f))
        assertEquals(-1, ContinuousMath.itemHeight(1080, 18, Float.NaN))
    }

    @Test
    fun centerPicksPageUnderMidline() {
        val tops = intArrayOf(-900, 600, 2100)
        val bottoms = intArrayOf(577, 2077, 3577)
        assertEquals(1, ContinuousMath.indexAtCenter(tops, bottoms, 1000, 24))
    }

    @Test
    fun gapBelowPageBelongsToThatPage() {
        val tops = intArrayOf(0, 1500)
        val bottoms = intArrayOf(1477, 2977)
        // 中线落在页间缝里时仍算上一页，不会出现「没有当前页」
        assertEquals(0, ContinuousMath.indexAtCenter(tops, bottoms, 1490, 24))
        assertEquals(1, ContinuousMath.indexAtCenter(tops, bottoms, 1500, 24))
    }

    @Test
    fun noHitReturnsMinusOne() {
        assertEquals(-1, ContinuousMath.indexAtCenter(IntArray(0), IntArray(0), 500, 24))
        assertEquals(-1, ContinuousMath.indexAtCenter(intArrayOf(2000), intArrayOf(3000), 500, 24))
    }

    @Test
    fun zoomScalesItemHeightFromRoundedBase() {
        val base = ContinuousMath.itemHeight(1080, 18, 0.707f)
        assertEquals(Math.round(base * 2f), ContinuousMath.itemHeight(1080, 18, 0.707f, 2f))
    }

    @Test
    fun anchoredZoomKeepsFocusPointStill() {
        val hostW = 1080f
        val left = 18f
        val oldS = 1.5f
        val newS = 2.5f
        val oldTx = -120f
        val fx = 700f
        val c = hostW / 2
        val oldLeft = c + (left - c) * oldS + oldTx
        val pageX = (fx - oldLeft) / oldS
        val newTx = ContinuousMath.anchoredTx(hostW, left, oldS, newS, oldTx, fx)
        val newLeft = c + (left - c) * newS + newTx
        assertEquals(fx, newLeft + pageX * newS, 0.01f)
    }
}
