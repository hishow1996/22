package com.moye.pdfink

/**
 * 页面显示模式。竖屏默认按宽度适配，过高页面允许纵向平移；
 * 横屏保留 FIT / WIDTH / DOUBLE 三种模式。
 * 与「题目 / 草稿对开」互斥：双页并排时 effectiveSpread 会自动降级。
 *
 * DOUBLE 下 ViewPager2 的一个 item 是「一组两页」：itemCount 减半，
 * 组内主页 = viewPos*2、副页 = viewPos*2+1。这样每个逻辑页只有唯一一个
 * 可写 InkView，不会与相邻 item 的预加载笔迹键撞车。
 * 对外仍以「逻辑页」为准（书签 / 导航 / 会话 / 导出 / 缩略图全用逻辑页），
 * 由 MainActivity 的 logicalPage() / viewPos() 做一层薄映射。
 */
object DisplayMode {
    const val FIT = 0        // 整页 fit 进可用区域
    const val WIDTH = 1      // 按宽度铺满，纵向可平移
    const val DOUBLE = 2     // 相邻两页并排

    fun effective(mode: Int, landscape: Boolean): Int =
        if (landscape) mode.coerceIn(FIT, DOUBLE) else WIDTH

    fun isDouble(mode: Int, landscape: Boolean) =
        effective(mode, landscape) == DOUBLE

    /** ViewPager2 的 item 数：双页时每两项合成一组。 */
    fun viewCount(mode: Int, landscape: Boolean, count: Int): Int =
        if (isDouble(mode, landscape)) (count + 1) / 2 else count

    /** display position → 主页逻辑页。 */
    fun logical(mode: Int, landscape: Boolean, viewPos: Int): Int =
        if (isDouble(mode, landscape)) viewPos * 2 else viewPos

    /** 逻辑页 → display position。 */
    fun viewPos(mode: Int, landscape: Boolean, page: Int): Int =
        if (isDouble(mode, landscape)) page / 2 else page

    /** 某一组要渲染的逻辑页（主 + 可选副页）。viewPos 是 display position。 */
    fun pages(mode: Int, landscape: Boolean, viewPos: Int, count: Int): IntArray {
        if (!isDouble(mode, landscape)) return intArrayOf(viewPos)
        val p0 = viewPos * 2
        return if (p0 + 1 < count) intArrayOf(p0, p0 + 1) else intArrayOf(p0)
    }

    /** 页码显示：双页时给出「3–4 / 15」这种区间，不写说明文字。 */
    fun label(mode: Int, landscape: Boolean, viewPos: Int, count: Int): String {
        if (!isDouble(mode, landscape)) return "${viewPos + 1} / $count"
        val p0 = viewPos * 2
        return if (p0 + 1 < count) "${p0 + 1}–${p0 + 2} / $count"
        else "${p0 + 1} / $count"
    }
}
