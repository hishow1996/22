package com.moye.pdfink

import kotlin.math.max
import kotlin.math.min

/**
 * v48：局部放大书写窗的坐标换算。纯 JVM。
 *
 * 窗口 = 原页上的一小块（归一化坐标，左上角 + 宽高），放大铺满底部的书写条。
 * 在条里写的点按比例落回原页；写到条的右侧时窗口自动往右挪。
 */
object LoupeMath {

    /** 窗口宽占整页宽的比例：越小放得越大。 */
    const val DEFAULT_FRAC = 0.34f
    const val MIN_FRAC = 0.18f
    const val MAX_FRAC = 0.6f

    /** 写到窗口宽度的这个位置以右，抬笔后窗口自动右移。 */
    const val ADVANCE_AT = 0.72f
    /** 自动 / 手动横移的步长（窗口宽度的比例）。 */
    const val STEP = 0.5f
    /** 换行：窗口高度的这个比例往下挪（留一点重叠，方便对照上一行）。 */
    const val LINE_STEP = 0.6f

    /**
     * 窗口高（归一化）。条的像素宽高比 sw:sh，页的宽高比 pageRatio = 页宽/页高。
     * 窗口在页上的像素宽 = frac * 页宽，高 = 该宽 * sh / sw，再除以页高归一化。
     */
    fun winH(frac: Float, pageRatio: Float, sw: Float, sh: Float): Float {
        if (sw <= 0f || sh <= 0f || pageRatio <= 0f) return 0f
        return min(1f, frac * pageRatio * sh / sw)
    }

    fun clampX(x: Float, ww: Float): Float = x.coerceIn(0f, max(0f, 1f - ww))
    fun clampY(y: Float, wh: Float): Float = y.coerceIn(0f, max(0f, 1f - wh))

    /** 条内像素点 → 页上归一化坐标。 */
    fun toPageX(px: Float, sw: Float, winX: Float, ww: Float): Float = winX + px / sw * ww
    fun toPageY(py: Float, sh: Float, winY: Float, wh: Float): Float = winY + py / sh * wh

    /**
     * 抬笔后窗口是否要右移：最后一点落在窗口 ADVANCE_AT 以右。
     * 返回新的 winX（已夹在页内）；不用动返回 null。
     */
    fun advancedX(winX: Float, ww: Float, lastPageX: Float): Float? {
        if (ww <= 0f || (lastPageX - winX) / ww < ADVANCE_AT) return null
        val nx = clampX(winX + ww * STEP, ww)
        return if (nx > winX + 1e-6f) nx else null
    }

    /** 点一下小地图：窗口中心移到该点，夹在页内。 */
    fun centerOn(nx: Float, ny: Float, ww: Float, wh: Float): Pair<Float, Float> =
        clampX(nx - ww / 2f, ww) to clampY(ny - wh / 2f, wh)

    /**
     * 换行：x 回到这一行的起点，y 往下 LINE_STEP 个窗口高；
     * 已经到页底就停在页底。
     */
    fun nextLine(rowStartX: Float, winY: Float, ww: Float, wh: Float): Pair<Float, Float> =
        clampX(rowStartX, ww) to clampY(winY + wh * LINE_STEP, wh)

    /**
     * 窗口是否整个落在已渲染的底图里（逻辑像素）。落在里面就不用重新渲染。
     */
    fun covered(
        left: Float, top: Float, w: Float, h: Float,
        tileLeft: Float, tileTop: Float, tileW: Float, tileH: Float
    ): Boolean = left >= tileLeft - 0.5f && top >= tileTop - 0.5f &&
        left + w <= tileLeft + tileW + 0.5f && top + h <= tileTop + tileH + 0.5f
}
