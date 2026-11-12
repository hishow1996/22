package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 栏占位唯一出口、物理刘海避让、竖屏三态判定（纯 JVM）。 */
class ChromeLayoutTest {

    /** 3.0 密度下：baseTop 150px、baseBottom 144px、gap 18/12px，刘海 72px。 */
    private fun metrics(portrait: Boolean, hidden: Boolean, safeT: Int = 72,
                        safeL: Int = 0) = ChromeLayout.metrics(
        portrait = portrait, barsHidden = hidden,
        baseTop = 150, baseBottom = 144, gapTop = 18, gapBottom = 12,
        sysTop = 0, sysBottom = 0,
        safeL = safeL, safeT = safeT, safeR = 0, safeB = 0
    )

    @Test fun `portrait top bar content is pushed below the physical notch`() {
        val cm = metrics(portrait = true, hidden = false)
        // 荣耀 10 SHORT_EDGES：窗口画进刘海区，图标必须从 y=72 以下开始。
        assertEquals(72, cm.cutTop)
        assertEquals(240, cm.barTop)          // 150 + 72 + 18
        assertEquals(90, cm.topPaddingTop)    // 18 + 72
        assertEquals(156, cm.barBottom)
        assertEquals(12, cm.bottomPaddingBottom)
        assertEquals(0, cm.cutBottom)         // 竖屏底部无刘海
        assertEquals(0, cm.pagerLeft)
        assertEquals(0, cm.pagerRight)
    }

    @Test fun `notch clearance is counted once not three times`() {
        val cm = metrics(portrait = true, hidden = false)
        // barTop 已含 cutTop，topPaddingTop 是它的子集；阅读区只按 barTop
        // 扣一次。图标可用高度恒等于 baseTop，不会被刘海吃掉。
        assertEquals(150, cm.barTop - cm.topPaddingTop)
        assertTrue(cm.topPaddingTop >= cm.cutTop)
    }

    @Test fun `hidden bars let the page start at the very top`() {
        val cm = metrics(portrait = true, hidden = true)
        assertEquals(0, cm.barTop)
        assertEquals(0, cm.barBottom)
        // 顶栏消失后纸页从屏幕最顶开始，不留刘海灰条
        assertEquals(0, cm.hiddenPagerTop)
    }

    @Test fun `system hide notch area means zero app clearance`() {
        val cm = metrics(portrait = true, hidden = false, safeT = 0)
        assertEquals(0, cm.cutTop)
        assertEquals(168, cm.barTop)
        assertEquals(18, cm.topPaddingTop)
        assertEquals(0, cm.hiddenPagerTop)
    }

    @Test fun `negative cutout insets are clamped`() {
        val cm = metrics(portrait = true, hidden = false, safeT = -30)
        assertEquals(0, cm.cutTop)
        assertEquals(168, cm.barTop)
    }

    @Test fun `landscape still avoids rotated notch`() {
        // 横屏后刘海贴左边：safeT=0 / safeL=72，顶部不需要额外避让
        val cm = metrics(portrait = false, hidden = false, safeT = 0, safeL = 72)
        assertEquals(0, cm.cutTop)
        assertEquals(168, cm.barTop)
        assertEquals(18, cm.topPaddingTop)
        assertEquals(72, cm.pagerLeft)
        assertEquals(0, cm.hiddenPagerTop)
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
