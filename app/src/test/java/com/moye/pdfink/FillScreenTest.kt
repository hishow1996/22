package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** 智能铺满倍率：有栏/隐藏栏都填满高度，宽幅页钳位，横页不缩小。 */
class FillScreenTest {

    private fun pageH(availW: Int, ratio: Float, s: Float): Int =
        (availW * s / ratio).roundToInt()

    @Test fun `a4 portrait fills available height with bars`() {
        // 1080 宽 / 1935 可用高 / A4≈0.707 → 约 1.27x，上下零留白
        val ratio = 0.707f
        val s = PageContainer.fillScale(1080, 1935, ratio)
        assertTrue("scale must grow", s > 1.2f)
        assertTrue("scale under cap", s <= PageContainer.MAX_AUTO_SCALE)
        assertEquals(1935.0, pageH(1080, ratio, s).toDouble(), 2.0)
        assertTrue((1080 * s).roundToInt() > 1080)   // 横向有可平移的余量
    }

    @Test fun `hidden bars fill the whole screen`() {
        val ratio = 0.707f
        val s = PageContainer.fillScale(1080, 2340, ratio)
        assertEquals(2340.0, pageH(1080, ratio, s).toDouble(), 2.0)
    }

    @Test fun `wide page is capped instead of cropping most content`() {
        // pageH 基准仅 600，cover 会到 3.9x；必须钳到上限
        val s = PageContainer.fillScale(1080, 2340, 1.8f)
        assertEquals(PageContainer.MAX_AUTO_SCALE, s, 0.001f)
    }

    @Test fun `page already taller than viewport stays at 1x`() {
        // 不缩小：本来就能上下平移的页面不需要自动放大
        assertEquals(1f, PageContainer.fillScale(1080, 800, 0.3f), 0.001f)
    }

    @Test fun `invalid inputs are safe`() {
        assertEquals(1f, PageContainer.fillScale(0, 1935, 0.707f), 0.001f)
        assertEquals(1f, PageContainer.fillScale(1080, 0, 0.707f), 0.001f)
        assertEquals(1f, PageContainer.fillScale(1080, 1935, 0f), 0.001f)
    }

    @Test fun `fit modes are distinct`() {
        assertTrue(PageContainer.FIT != PageContainer.WIDTH)
        assertTrue(PageContainer.WIDTH != PageContainer.FILL)
    }
}
