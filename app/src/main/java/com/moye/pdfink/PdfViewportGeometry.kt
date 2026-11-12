package com.moye.pdfink

/**
 * Insets that define the actual PDF viewport inside the activity root.
 *
 * Toolbars occupy real space outside ViewPager2; when hidden, their insets are
 * exactly zero. In landscape, physical left/right cutout safety remains reserved.
 */
data class PdfContentInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun contentWidth(rootWidth: Int): Int =
        (rootWidth - left - right).coerceAtLeast(0)

    fun contentHeight(rootHeight: Int): Int =
        (rootHeight - top - bottom).coerceAtLeast(0)
}

object PdfViewportGeometry {
    /**
     * 隐藏工具栏后阅读区顶部取 hiddenSafeTop（来自
     * ChromeLayout.metrics().hiddenPagerTop，现为 0：纸页从屏幕最顶开始）。
     * 默认值 0 保持原有调用与单测语义不变。
     */
    fun contentInsets(
        landscape: Boolean,
        barsHidden: Boolean,
        safeLeft: Int,
        safeRight: Int,
        toolbarTop: Int,
        toolbarBottom: Int,
        hiddenSafeTop: Int = 0
    ): PdfContentInsets = PdfContentInsets(
        left = if (landscape) safeLeft.coerceAtLeast(0) else 0,
        top = if (barsHidden) hiddenSafeTop.coerceAtLeast(0)
        else toolbarTop.coerceAtLeast(0),
        right = if (landscape) safeRight.coerceAtLeast(0) else 0,
        bottom = if (barsHidden) 0 else toolbarBottom.coerceAtLeast(0)
    )
}
