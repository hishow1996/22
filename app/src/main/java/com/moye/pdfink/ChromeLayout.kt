package com.moye.pdfink

/**
 * 文档态栏占位的唯一计算出口（纯 JVM，便于单测）。
 *
 * v29 修掉了「同一刘海高度被算进顶栏高 + 顶栏 padding + 阅读区三处」的
 * 重复留白，但把竖屏 cutTop 一刀切归零，也删掉了物理避让 —— 荣耀 10
 * 在 SHORT_EDGES 下窗口确实延伸到刘海区，顶栏图标会被挖孔遮住（v30）。
 *
 * v30：cutTop 恢复取物理刘海深度，但**只用于顶栏自身**：
 *   barTop（容器总高，背景延伸到刘海区）与 topPaddingTop（图标起始 y）
 *   是包含关系，不再叠加到阅读区；阅读区顶部由 barTop 一次给出。
 *   隐藏栏时顶栏消失，但物理刘海仍在，阅读区顶部改为 hiddenPagerTop。
 * cutBottom 竖屏为 0（底部无刘海），横屏保留旋转后的安全区。
 */
data class ChromeMetrics(
    val barTop: Int,
    val barBottom: Int,
    val topPaddingTop: Int,
    val bottomPaddingBottom: Int,
    val pagerLeft: Int,
    val pagerRight: Int,
    val cutTop: Int,
    val cutBottom: Int,
    val hiddenPagerTop: Int
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
        // 物理刘海深度：DisplayCutout.safeInsetTop（API28+）或华为私有
        // HwNotchSizeUtil（EMUI 8.1）。状态栏虽已隐藏，挖孔仍在屏幕里，
        // SHORT_EDGES 下窗口会画进去 → 顶栏内容必须避开。
        // 系统开了「隐藏显示区域」时 NotchCompat 返回 0，不会双份留白。
        val cutTop = maxOf(sysTop, safeT).coerceAtLeast(0)
        val cutBottom = if (portrait) 0
        else maxOf(sysBottom, safeB).coerceAtLeast(0)
        return ChromeMetrics(
            barTop = if (barsHidden) 0 else baseTop + cutTop + gapTop,
            barBottom = if (barsHidden) 0 else baseBottom + cutBottom + gapBottom,
            // 图标从刘海下沿开始；barTop 已含 cutTop，二者是包含不是叠加，
            // 阅读区只按 barTop 扣一次，不再重复压矮。
            topPaddingTop = gapTop + cutTop,
            bottomPaddingBottom = gapBottom + cutBottom,
            pagerLeft = if (portrait) 0 else safeL,
            pagerRight = if (portrait) 0 else safeR,
            cutTop = cutTop,
            cutBottom = cutBottom,
            // 隐藏栏后纸页仍要避开物理刘海（FILL 会按新可用高重新铺满）。
            hiddenPagerTop = cutTop
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
