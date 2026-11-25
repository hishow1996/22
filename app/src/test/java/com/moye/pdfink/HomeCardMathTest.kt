package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCardMathTest {
    @Test fun a4PageGivesPortraitCover() {
        // A4 595×842 → 192 × 271.7 ≈ 272
        assertArrayEquals(intArrayOf(192, 272), HomeCardMath.coverSize(595, 842))
    }

    @Test fun landscapeAndTallPagesAreClamped() {
        assertArrayEquals(intArrayOf(192, 128), HomeCardMath.coverSize(1000, 400))   // 宽页：下限
        assertArrayEquals(intArrayOf(192, 384), HomeCardMath.coverSize(100, 1000))   // 长图：上限
    }

    @Test fun badPageSizeFallsBackTo34() {
        assertArrayEquals(intArrayOf(192, 256), HomeCardMath.coverSize(0, 0))
    }

    @Test fun progressIsPageOverTotal() {
        assertEquals(250, HomeCardMath.progressPermille(1, 4))
        assertEquals(1000, HomeCardMath.progressPermille(4, 4))
        assertEquals(1000, HomeCardMath.progressPermille(9, 4))   // 越界夹住
        assertEquals(250, HomeCardMath.progressPermille(0, 4))    // 第 0 页按第 1 页
        assertEquals(-1, HomeCardMath.progressPermille(3, 0))     // 无页数：隐藏
    }
}
