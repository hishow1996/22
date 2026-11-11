package com.moye.pdfink

/**
 * v31：竖屏三态的对齐决策表（纯 JVM，便于单测）。
 *
 * v29 为修「隐藏栏后大片留白」写了「不是 FIT 就一律 START|TOP」，
 * 结果把「适应宽度」也贴到顶栏下、底部堆出约 455px 米白 —— 两个
 * 「适应」模式一个居中一个靠顶，观感不统一。
 *
 * 现在按真实页高决定：
 *  - 适应屏幕 FIT  ：整页 contain，页高恒 ≤ 可用高 → 垂直居中
 *  - 适应宽度 WIDTH：页高 < 可用高 → 上下均分；页高 ≥ 可用高 → 回页顶可拖
 *  - 铺满屏幕 FILL ：严格 cover，四边零留白 → 靠左上（v29 原样）
 * 横屏 FIT / WIDTH / DOUBLE 一律 CENTER，一行不改。
 */
object PageAlignment {
    const val CENTER = 0
    const val TOP_START = 1

    /** 与页面容器的 FILL 常量同值；不引用 Android 类，保证本表纯 JVM 可测。 */
    const val MODE_FIT = 0
    const val MODE_WIDTH = 1
    const val MODE_FILL = 2

    fun resolve(
        portrait: Boolean,
        fitMode: Int,
        pageHeight: Float,
        availableHeight: Float
    ): Int {
        if (!portrait) return CENTER
        if (fitMode == MODE_FILL) return TOP_START
        // 宿主还没测量完成时不能凭 0 高度误判成「超出」。
        if (availableHeight <= 0f || pageHeight <= 0f) return CENTER
        // FIT 由 fitPageSize 保证不超过视口；WIDTH 的长页才需要回页顶。
        return if (pageHeight > availableHeight * 1.001f) TOP_START else CENTER
    }
}
