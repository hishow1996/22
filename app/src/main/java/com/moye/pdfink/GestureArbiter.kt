package com.moye.pdfink

/**
 * 浏览模式单指手势仲裁。抽成纯函数便于 JVM 单测。
 *
 * v26：竖屏 ViewPager2 已切 VERTICAL，跨页纵向滚动由系统 RecyclerView
 * 原生接管（跟手 / fling / 回弹 / 预取）。这里只负责两件事：
 * ① 页面自身有纵向超出（横屏 WIDTH）或放大后的页内平移；
 * ② 竖屏下补一次横向离散翻页，保住「左右滑翻页」。
 * 纵向且无可平移量时一律返回 false，把事件让给 pager，不抢不吞。
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

    /**
     * v26：竖屏 VERTICAL 后横向单指滑不再触发 pager 滚动。
     * 只在「未放大 + 明显横向 + 超阈值」时补一次离散翻页；
     * 纵向一律留给 pager 原生滚动，放大态留给 isZoomedPan。
     * 1.2 倍方向比留出手写斜划的容差，避免轻微斜向误翻页。
     */
    fun isHorizontalFlip(
        dxAbs: Float, dyAbs: Float, threshold: Float, enabled: Boolean
    ): Boolean = enabled && dxAbs > threshold && dxAbs > dyAbs * 1.2f
}
