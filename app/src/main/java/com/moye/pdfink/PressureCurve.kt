package com.moye.pdfink

/**
 * v43 压感：把 MotionEvent.pressure 映射成笔迹宽度系数。纯 JVM，便于单测。
 *
 * 既有链路其实已经采了 getHistoricalPressure / pressure 并写进 Mark.ws，
 * 这轮补的是三件事：起笔点也吃 DOWN 的压力、四档强度可调、曲线抽成可测函数。
 * 手指输入（无真实压感）与 OFF 档一律返回 1f，速度调制仍由 InkView 单独算，不重复。
 */
object PressureCurve {
    const val OFF = 0
    const val SOFT = 1
    const val NORMAL = 2
    const val STRONG = 3
    const val DEFAULT = NORMAL

    fun coerce(level: Int): Int = level.coerceIn(OFF, STRONG)

    /** 返回笔宽倍率；只在触控笔 + 非 OFF 档生效。 */
    fun factor(level: Int, pressure: Float, stylus: Boolean): Float {
        if (!stylus || level == OFF) return 1f
        val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0.5f
        return when (coerce(level)) {
            SOFT -> (0.85f + 0.30f * p).coerceIn(0.80f, 1.15f)
            NORMAL -> (0.65f + 0.50f * p).coerceIn(0.70f, 1.30f)
            STRONG -> (0.40f + 0.90f * p).coerceIn(0.45f, 1.65f)
            else -> 1f
        }
    }

    /** 起笔点：从 1f 平滑过渡到DOWN 压力对应的倍率，避免首段突然变粗。 */
    fun startFactor(level: Int, pressure: Float, stylus: Boolean): Float =
        smooth(1f, factor(level, pressure, stylus))

    /** 与速度系数共用的低通，避免相邻采样点宽度跳变。 */
    fun smooth(prev: Float, target: Float): Float =
        (0.65f * prev + 0.35f * target).coerceIn(0.35f, 1.8f)

    /** 四档循环，供菜单按钮用。 */
    fun next(level: Int): Int = (coerce(level) + 1) % 4
}
