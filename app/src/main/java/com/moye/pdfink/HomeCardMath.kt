package com.moye.pdfink

import kotlin.math.roundToInt

/** v61：书架卡片用到的纯计算（不依赖 Android，可本地测试）。 */
object HomeCardMath {
    const val COVER_W = 192
    private const val COVER_MIN_H = 128
    private const val COVER_MAX_H = 384

    /** 封面位图尺寸：宽固定，高按第一页比例，夹在 [128, 384]；页面尺寸异常时给 3:4。 */
    fun coverSize(pageW: Int, pageH: Int): IntArray {
        if (pageW <= 0 || pageH <= 0) return intArrayOf(COVER_W, COVER_W * 4 / 3)
        val h = (COVER_W.toFloat() * pageH / pageW).roundToInt()
        return intArrayOf(COVER_W, h.coerceIn(COVER_MIN_H, COVER_MAX_H))
    }

    /** 进度条填充比例 0..1000（千分）；没有页数概念返回 -1（隐藏进度条）。 */
    fun progressPermille(curPage: Int, pages: Int): Int {
        if (pages <= 0) return -1
        val cur = curPage.coerceIn(1, pages)
        return (cur * 1000f / pages).roundToInt().coerceIn(0, 1000)
    }
}
