package com.moye.pdfink


/**
 * v53：文字批注框的纯数学（字号、宽度 / 位置限位、屏幕 ↔ 页面换算）。不碰 Android，可单测。
 * 排版和绘制在 [TextBoxGeometry]。
 */
object TextBoxMath {
    /** 字号（页面短边的比例）连续可调：拖角缩放时不分档。印刷正文大约 0.014~0.02。 */
    const val MIN_SIZE = 0.012f
    const val MAX_SIZE = 0.09f
    const val DEFAULT_SIZE = 0.026f

    fun clampSize(frac: Float): Float = frac.coerceIn(MIN_SIZE, MAX_SIZE)

    /**
     * 拖右下角等比缩放：宽度从 startW 拖到 newW，字号同比例变。
     * 字号到上下限就停在限值（宽度仍可继续变，字不再变）。
     */
    fun scaledSize(startSize: Float, startW: Float, newW: Float): Float {
        if (startW <= 0f) return clampSize(startSize)
        return clampSize(startSize * newW / startW)
    }

    /** 新文字框的默认宽（页宽的比例），自动换行；最窄不小于 [MIN_W]。 */
    const val DEFAULT_W = 0.6f
    const val MIN_W = 0.12f

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
    /** v62：文字批注一律只显示文字，不再画底框 / 边框（旧数据里的底框标志忽略）。 */
    @Suppress("UNUSED_PARAMETER")
    fun hasBg(ws: FloatArray?): Boolean = false

    fun bgWs(bg: Boolean): FloatArray = floatArrayOf(if (bg) 1f else 0f, 0f)
}

/** 导出轮廓：box = 归一化 l,t,r,b；contours = 字形轮廓折线（归一化 x,y 交错，闭合不重复首点）。 */
class TextBoxOutline(val box: FloatArray, val contours: List<FloatArray>)
