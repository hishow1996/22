package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** v29：栏占位唯一出口、严格 cover、竖屏三态判定（纯 JVM）。 */
class ChromeLayoutTest {

    /** 3.0 密度下：baseTop 150px、baseBottom 144px、gap 18/12px，刘海 72px。 */
    private fun metrics(portrait: Boolean, hidden: Boolean, safeT: Int = 72,
                        safeL: Int = 0) = ChromeLayout.metrics(
        portrait = portrait, barsHidden = hidden,
        baseTop = 150, baseBottom = 144, gapTop = 18, gapBottom = 12,
        sysTop = 0, sysBottom = 0,
        safeL = safeL, safeT = safeT, safeR = 0, safeB = 0
    )

    @Test fun `portrait bars no longer absorb notch height`() {
        val cm = metrics(portrait = true, hidden = false)
        // 旧版：barTop = 150 + 72 + 18 = 240，图标上方凭空多 72px 白带
        assertEquals(168, cm.barTop)
        assertEquals(156, cm.barBottom)
        assertEquals(18, cm.topPaddingTop)
        assertEquals(12, cm.bottomPaddingBottom)
        assertEquals(0, cm.pagerLeft)
        assertEquals(0, cm.pagerRight)
        assertEquals(0, cm.cutBottom)
    }

    @Test fun `hidden bars leave zero padding so pager is full screen`() {
        val cm = metrics(portrait = true, hidden = true)
        assertEquals(0, cm.barTop)
        assertEquals(0, cm.barBottom)
    }

    @Test fun `landscape still avoids rotated notch`() {
        val cm = metrics(portrait = false, hidden = false, safeL = 72)
        assertEquals(240, cm.barTop)
        assertEquals(90, cm.topPaddingTop)
        assertEquals(72, cm.pagerLeft)
    }

    private fun pageH(availW: Int, ratio: Float, s: Float): Int =
        (availW * s / ratio).roundToInt()

    @Test fun `a4 with bars fills exactly the between-bars area`() {
        val s = PageContainer.fillScaleExact(1080, 1935, 0.707f)
        assertTrue(s > 1.2f)
        assertEquals(1935, pageH(1080, 0.707f, s))
        assertTrue((1080 * s).roundToInt() > 1080) // 横向超出可平移
    }

    @Test fun `a4 with hidden bars fills the whole screen`() {
        val s = PageContainer.fillScaleExact(1080, 2340, 0.707f)
        assertEquals(2340, pageH(1080, 0.707f, s))
    }

    @Test fun `tall page fills width and overflows vertically instead of leaving white`() {
        val s = PageContainer.fillScaleExact(1080, 1935, 0.3f)
        assertEquals(1f, s, 0.001f)
        val h = pageH(1080, 0.3f, s)
        assertTrue("height must overflow viewport", h > 1935)
        assertEquals(1080, (1080 * s).roundToInt())
    }

    @Test fun `wide page is no longer capped at 1_8x`() {
        // v19 的 1.8x 会让页高只有 1080*1.8/1.8=1080，上下留 1260px 白。
        val s = PageContainer.fillScaleExact(1080, 2340, 1.8f)
        assertTrue(s > 3f)
        assertEquals(2340, pageH(1080, 1.8f, s))
    }

    @Test fun `invalid inputs are safe`() {
        assertEquals(1f, PageContainer.fillScaleExact(0, 1935, 0.707f), 0.001f)
        assertEquals(1f, PageContainer.fillScaleExact(1080, 0, 0.707f), 0.001f)
        assertEquals(1f, PageContainer.fillScaleExact(1080, 1935, 0f), 0.001f)
        assertTrue(abs(PageContainer.fillScaleExact(1080, 999999, 20f) -
            PageContainer.MAX_FILL_SCALE) < 0.001f)
    }

    @Test fun `portrait three modes are mutually exclusive`() {
        assertEquals(PageContainer.FILL,
            FitModeResolver.resolve(true, true, false, false))
        assertEquals(PageContainer.FIT,
            FitModeResolver.resolve(true, false, true, false))
        assertEquals(PageContainer.WIDTH,
            FitModeResolver.resolve(true, false, false, false))
        // 旧版这里会因会话残留 FIT 退回 contain，三态看起来一样
        assertEquals(PageContainer.FILL,
            FitModeResolver.resolve(true, true, true, false))
    }

    @Test fun `landscape modes are untouched`() {
        assertEquals(PageContainer.WIDTH,
            FitModeResolver.resolve(false, true, false, true))
        assertEquals(PageContainer.FIT,
            FitModeResolver.resolve(false, true, false, false))
    }
}
