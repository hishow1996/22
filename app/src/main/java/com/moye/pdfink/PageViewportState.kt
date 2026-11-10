package com.moye.pdfink

/**
 * 每页的缩放与滚动状态。panX / panY 是当前可移动范围的归一化比例，
 * 不保存像素值；没有滚动范围的模式下也保留最近位置，切回适宽时可恢复。
 */
data class PageViewportState(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    fun normalized(): PageViewportState = PageViewportState(
        zoom = safe(zoom, 1f).coerceIn(1f, 4f),
        panX = safe(panX, 0f).coerceIn(-1f, 1f),
        panY = safe(panY, 0f).coerceIn(-1f, 1f)
    )

    companion object {
        /** 同一页的 FIT / WIDTH / DOUBLE 阅读位置分别保存。 */
        fun storageKey(page: Int, mode: Int): Int {
            val safePage = page.coerceAtLeast(0).coerceAtMost((Int.MAX_VALUE - 2) / 3)
            return safePage * 3 + mode.coerceIn(DisplayMode.FIT, DisplayMode.DOUBLE)
        }
    }

    private fun safe(value: Float, fallback: Float): Float =
        if (value.isNaN() || value.isInfinite()) fallback else value
}

/** ZoomHost 与单元测试共用的缩放锚点 / 归一化平移计算。 */
object ZoomTransform {

    fun anchoredTranslation(
        oldScale: Float,
        newScale: Float,
        viewportSize: Float,
        focus: Float,
        translation: Float
    ): Float {
        if (oldScale <= 0f) return translation
        val centeredFocus = focus - viewportSize * 0.5f
        return centeredFocus -
            (centeredFocus - translation) * (newScale / oldScale)
    }

    fun fraction(offset: Float, limit: Float): Float =
        if (limit <= 0f) 0f else (offset / limit).coerceIn(-1f, 1f)

    fun offset(limit: Float, fraction: Float): Float =
        if (limit <= 0f) 0f else limit * fraction.coerceIn(-1f, 1f)
}
