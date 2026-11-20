package com.moye.pdfink

import kotlin.math.abs

/**
 * v53：文字批注框的纯数学（字号档、宽度 / 位置限位、屏幕 ↔ 页面换算）。不碰 Android，可单测。
 * 排版和绘制在 [TextBoxGeometry]。
 */
object TextBoxMath {
    /** 字号四档（页面短边的比例）：小 / 中 / 大 / 特大。印刷正文大约 0.014~0.02。 */
    val SIZES = floatArrayOf(0.018f, 0.026f, 0.036f, 0.05f)
    const val DEFAULT_SIZE = 1

    /** 新文字框的默认宽（页宽的比例），自动换行；最窄不小于 [MIN_W]。 */
    const val DEFAULT_W = 0.6f
    const val MIN_W = 0.12f

    fun size(idx: Int): Float = SIZES[idx.coerceIn(0, SIZES.size - 1)]

    /** 存下来的字号比例 → 最近的一档（旧数据 / 手改数据也不会出错）。 */
    fun nearestSize(frac: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in SIZES.indices) {
            val d = abs(SIZES[i] - frac)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun clampIdx(idx: Int): Int = idx.coerceIn(0, SIZES.size - 1)

    /** 字号档 +1 / -1，到头不动。 */
    fun step(idx: Int, delta: Int): Int = clampIdx(idx + delta)

    /** 宽度限位：不窄于 MIN_W、不超过页面（左边已定时放不下就缩窄，缩到 MIN_W 为止）。 */
    fun clampW(wNorm: Float): Float = wNorm.coerceIn(MIN_W, 1f)

    /** 左边起点限位：整个框留在页内。 */
    fun clampX(x0: Float, wNorm: Float): Float = x0.coerceIn(0f, (1f - wNorm).coerceAtLeast(0f))

    /** 新建时默认宽：点在页面右侧、右边放不下默认宽就缩窄（不小于 MIN_W），而不是挤出页面。 */
    fun initialW(x0: Float): Float = clampW(minOf(DEFAULT_W, 1f - x0))

    /** 屏幕上拖出的宽度（像素）换成页面宽度比例。screenPerPx = 屏幕像素 / 页面布局像素。 */
    fun normWidth(screenW: Float, screenPerPx: Float, pageW: Float): Float {
        if (screenPerPx <= 0f || pageW <= 0f) return DEFAULT_W
        return clampW(screenW / screenPerPx / pageW)
    }

    /** 底色标志：ws[0] ≥ 0.5 有底；缺省有底。 */
    fun hasBg(ws: FloatArray?): Boolean = (ws?.getOrNull(0) ?: 1f) >= 0.5f

    fun bgWs(bg: Boolean): FloatArray = floatArrayOf(if (bg) 1f else 0f, 0f)
}

/** 导出轮廓：box = 归一化 l,t,r,b；contours = 字形轮廓折线（归一化 x,y 交错，闭合不重复首点）。 */
class TextBoxOutline(val box: FloatArray, val contours: List<FloatArray>)
