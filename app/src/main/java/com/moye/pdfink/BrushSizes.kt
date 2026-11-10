package com.moye.pdfink

/**
 * 笔与橡皮的粗细档位：数值、默认档、UI 预览尺寸的唯一来源。
 * 想改档数只动这里，主工具条、竖屏浮层、笔刷预设、单测会自动跟着变。
 *
 * 数值是按 PDF 页宽归一化的笔宽，所以缩放 / 旋转 / 导出尺寸变化时
 * 线条始终贴合题目，不需要为不同屏幕准备另一套数值。
 */
object BrushSizes {

    /** 5 档：极细 → 细 → 中 → 粗 → 特粗（约 0.25% → 1.9% 页宽）。 */
    val PEN = floatArrayOf(0.0025f, 0.0045f, 0.0075f, 0.012f, 0.019f)

    /** 橡皮比笔粗一个量级，每档都明显大于对应笔档，擦一次就够。 */
    val ERASER = floatArrayOf(0.016f, 0.026f, 0.040f, 0.060f, 0.088f)

    /** 5 档取中间档，首次打开不偏细也不偏粗。 */
    const val DEFAULT = 2

    val COUNT: Int get() = PEN.size

    /** 预览条高度（dp）：等距递增，最粗 15dp 仍放得进 22dp 的 holder。 */
    fun previewDp(i: Int): Int = 3 + i * 3
}
