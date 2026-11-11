package com.moye.pdfink

/**
 * v29：文档态栏占位的唯一计算出口（纯 JVM，便于单测）。
 *
 * 旧版把同一个刘海高度同时算进「顶栏高度 + 顶栏顶部 padding +
 * pager paddingTop」三处，屏幕顶部就多出一条白带，内容区又被压矮。
 * 状态栏 / 导航栏已彻底隐藏、竖屏也不延伸进刘海区，因此竖屏一律 0；
 * 横屏旋转后刘海贴左边，仅保留左右与底部的安全区避让。
 */
data class ChromeMetrics(
    val barTop: Int,
    val barBottom: Int,
    val topPaddingTop: Int,
    val bottomPaddingBottom: Int,
    val pagerLeft: Int,
    val pagerRight: Int,
    val cutBottom: Int
)

object ChromeLayout {

    fun metrics(
        portrait: Boolean,
        barsHidden: Boolean,
        baseTop: Int,
        baseBottom: Int,
        gapTop: Int,
        gapBottom: Int,
        sysTop: Int,
        sysBottom: Int,
        safeL: Int,
        safeT: Int,
        safeR: Int,
        safeB: Int
    ): ChromeMetrics {
        // v29：竖屏不延伸进刘海区，cutout 的 safeInsetTop 不再参与栏高。
        val cutTop = if (portrait) 0 else maxOf(sysTop, safeT)
        val cutBottom = if (portrait) 0 else maxOf(sysBottom, safeB)
        return ChromeMetrics(
            barTop = if (barsHidden) 0 else baseTop + cutTop + gapTop,
            barBottom = if (barsHidden) 0 else baseBottom + cutBottom + gapBottom,
            topPaddingTop = gapTop + cutTop,
            bottomPaddingBottom = gapBottom + cutBottom,
            pagerLeft = if (portrait) 0 else safeL,
            pagerRight = if (portrait) 0 else safeR,
            cutBottom = cutBottom
        )
    }
}

/**
 * v29：竖屏三态的唯一判定（纯 JVM，便于单测）。
 * 旧判定要求 mode == WIDTH 才进 FILL，会话里残着 FIT 就悄悄退回 contain，
 * 于是「适应屏幕 / 适应宽度 / 铺满屏幕」看起来都一样留白。
 */
object FitModeResolver {
    fun resolve(
        portrait: Boolean,
        fillScreen: Boolean,
        displayModeIsFit: Boolean,
        landscapeModeIsWidth: Boolean
    ): Int = when {
        portrait && fillScreen -> PageContainer.FILL
        portrait && displayModeIsFit -> PageContainer.FIT
        portrait -> PageContainer.WIDTH
        landscapeModeIsWidth -> PageContainer.WIDTH
        else -> PageContainer.FIT
    }
}
