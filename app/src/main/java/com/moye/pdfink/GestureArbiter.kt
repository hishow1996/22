package com.moye.pdfink

/**
 * 浏览模式单指手势仲裁：只把「页面有纵向超出 + 明显纵向」判给自己，
 * 横向一律留给 ViewPager2 翻页。抽成纯函数便于 JVM 单测。
 */
object GestureArbiter {
    fun isVerticalPan(
        dxAbs: Float, dyAbs: Float, threshold: Float, canPan: Boolean
    ): Boolean = canPan && dyAbs > threshold && dyAbs > dxAbs

    /** 已放大：横向翻页被禁用，单指任意方向拖动都应改成平移。 */
    fun isZoomedPan(
        dxAbs: Float, dyAbs: Float, threshold: Float, zoomed: Boolean
    ): Boolean = zoomed && maxOf(dxAbs, dyAbs) > threshold

    /** 页面有纵向超出：WIDTH 模式超出量 > 0，或已放大。 */
    fun canPanY(panExtraY: Float, scale: Float): Boolean =
        panExtraY > 0f || scale > 1.01f
}
