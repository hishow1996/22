package com.moye.pdfink

/**
 * v47：手指书写偏移。
 * 手指按在页面上会盖住正在写的地方，接触点又是一整块指腹，肉眼很难判断落点在哪。
 * 打开后，落笔点上移到手指上方（偏移量按档位），并在落点画一个小光标环、
 * 与指尖之间拉一条淡线，写的时候看得见笔迹，也知道笔会落在哪。
 * 只对手指生效；触控笔本来就是笔尖直接落点，不偏移。
 */
object FingerOffset {
    const val OFF = 0
    const val MAX_LEVEL = 3

    /** 各档偏移量（dp）：关 / 小 / 中 / 大。指腹半径约 24dp，中档刚好露出指尖上方。 */
    fun dp(level: Int): Int = when (level) {
        1 -> 32
        2 -> 48
        3 -> 64
        else -> 0
    }

    fun next(level: Int): Int = if (level >= MAX_LEVEL || level < 0) OFF else level + 1

    fun clamp(level: Int): Int = level.coerceIn(OFF, MAX_LEVEL)

    /** 便签是「点一下放在这里」，直接落在手指下更直观，不偏移；擦除 / 套索 / 各种笔都需要精确落点。 */
    fun applies(offsetPx: Int, isFinger: Boolean, tool: Int): Boolean =
        offsetPx > 0 && isFinger && tool != Tools.NOTE
}
