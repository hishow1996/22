package com.moye.pdfink

import kotlin.math.ceil
import kotlin.math.roundToInt

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
        zoom = safe(zoom, 1f).coerceIn(ZoomTransform.MIN_SCALE, 4f),
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
    const val MIN_SCALE = 0.5f

    /** 页面缩小到视口以内时居中；内容超出时仅开放真实溢出范围。 */
    fun panLimit(contentSize: Int, bucket: Float, viewportSize: Int): Float =
        ((contentSize.coerceAtLeast(0) * bucket.coerceAtLeast(0f)) -
            viewportSize.coerceAtLeast(0)).coerceAtLeast(0f) * 0.5f

    /** 从局部屏幕坐标反算页面逻辑坐标，供所有笔迹工具共用。 */
    fun pageCoordinate(screen: Float, origin: Float, scale: Float): Float =
        if (scale <= 0f) screen else (screen + origin) / scale

    fun renderToDisplayFactor(scale: Float, bucket: Float): Float =
        if (bucket <= 0f) 1f else scale / bucket

    /**
     * Calculate tile and ink coordinates for one child page.
     * Scale is anchored at the ZoomHost center, so differently-sized pages
     * in one spread follow the same gesture without sharing local dimensions.
     */
    fun pageViewportMapping(
        hostWidth: Int,
        hostHeight: Int,
        frame: PageFrame,
        scale: Float,
        bucket: Float,
        translationX: Float,
        translationY: Float
    ): PageViewportMapping {
        val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeBucket = bucket.takeIf { it.isFinite() && it > 0f } ?: 1f
        val hostW = hostWidth.coerceAtLeast(1)
        val hostH = hostHeight.coerceAtLeast(1)
        val displayFactor = safeScale / safeBucket

        val displayedLeft = hostW * 0.5f +
            (frame.left - hostW * 0.5f) * safeScale + translationX
        val displayedTop = hostH * 0.5f +
            (frame.top - hostH * 0.5f) * safeScale + translationY

        return PageViewportMapping(
            logicalWidth = (frame.width.coerceAtLeast(1) * safeBucket)
                .roundToInt().coerceAtLeast(1),
            logicalHeight = (frame.height.coerceAtLeast(1) * safeBucket)
                .roundToInt().coerceAtLeast(1),
            originX = ZoomTransform.canvasTranslationOffset(frame.left, displayedLeft),
            originY = ZoomTransform.canvasTranslationOffset(frame.top, displayedTop),
            visibleLeft = -displayedLeft / displayFactor,
            visibleTop = -displayedTop / displayFactor,
            visibleWidth = ceil(hostW / displayFactor).toInt().coerceAtLeast(1),
            visibleHeight = ceil(hostH / displayFactor).toInt().coerceAtLeast(1)
        )
    }

    /** Translation range for one page or a combined two-page / question-draft spread. */
    fun translationRange(
        viewportSize: Int,
        contentStart: Float,
        contentEnd: Float,
        scale: Float
    ): TranslationRange {
        val size = viewportSize.coerceAtLeast(0)
        if (size <= 0 || !contentStart.isFinite() || !contentEnd.isFinite() ||
            contentEnd <= contentStart
        ) return TranslationRange()

        val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val center = size * 0.5f
        val displayedStart = center + (contentStart - center) * safeScale
        val displayedEnd = center + (contentEnd - center) * safeScale
        if (displayedEnd - displayedStart <= size) return TranslationRange()

        return TranslationRange(
            minimum = size - displayedEnd,
            maximum = -displayedStart
        )
    }

    /** Canvas 的 translate 在 scale 前执行，故平移量保留屏幕像素单位。 */
    fun canvasTranslationOffset(baseOrigin: Float, visibleOrigin: Float): Float =
        if (baseOrigin.isFinite() && visibleOrigin.isFinite()) {
            baseOrigin - visibleOrigin
        } else 0f

    fun displayToRender(value: Float, scale: Float, bucket: Float): Float =
        if (scale <= 0f) value else value * bucket / scale

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
