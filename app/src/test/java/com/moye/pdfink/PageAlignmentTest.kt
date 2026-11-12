package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

/** 适应屏幕 / 适应宽度居中，铺满屏幕（页宽贴满、靠顶），横屏不受影响。 */
class PageAlignmentTest {

    private val availH = 2016f

    @Test
    fun fitPageIsVerticallyCentered() {
        assertEquals(
            PageAlignment.CENTER,
            PageAlignment.resolve(true, PageAlignment.MODE_FIT, 1528f, availH)
        )
    }

    @Test
    fun widthPageShorterThanViewportIsCentered() {
        // v29 旧行为：非 FIT 一律 TOP_START → 底部堆约 455px 空白。
        assertEquals(
            PageAlignment.CENTER,
            PageAlignment.resolve(true, PageAlignment.MODE_WIDTH, 1528f, availH)
        )
    }

    @Test
    fun widthPageTallerThanViewportReturnsToTop() {
        // 长页仍回页顶，保证能上下拖看完整页，不会被居中裁掉两头。
        assertEquals(
            PageAlignment.TOP_START,
            PageAlignment.resolve(true, PageAlignment.MODE_WIDTH, 2800f, availH)
        )
    }

    @Test
    fun fillAlwaysUsesTopStartEvenForShortPages() {
        assertEquals(
            PageAlignment.TOP_START,
            PageAlignment.resolve(true, PageAlignment.MODE_FILL, 900f, availH)
        )
        assertEquals(
            PageAlignment.TOP_START,
            PageAlignment.resolve(true, PageAlignment.MODE_FILL, 2016f, availH)
        )
    }

    @Test
    fun landscapeNeverChangesAlignment() {
        for (mode in listOf(
            PageAlignment.MODE_FIT,
            PageAlignment.MODE_WIDTH,
            PageAlignment.MODE_FILL
        )) {
            assertEquals(
                PageAlignment.CENTER,
                PageAlignment.resolve(false, mode, 2800f, availH)
            )
        }
    }

    @Test
    fun unmeasuredHostDoesNotMistakePageForOversized() {
        assertEquals(
            PageAlignment.CENTER,
            PageAlignment.resolve(true, PageAlignment.MODE_WIDTH, 1528f, 0f)
        )
        assertEquals(
            PageAlignment.CENTER,
            PageAlignment.resolve(true, PageAlignment.MODE_FIT, 0f, availH)
        )
    }
}
