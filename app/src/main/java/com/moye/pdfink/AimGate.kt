package com.moye.pdfink

import kotlin.math.hypot

/**
 * v48：手指偏移的「先瞄准、再落笔」。纯 JVM。
 *
 * 以前手指一按下就开始出墨，光标环只在按着的时候才看得见，想看清落点只能一直按住，
 * 可一动就写出去了。现在：
 *  - 手指按下：只出现瞄准环，**不出墨**（想看清落点，轻按一下就行，不用长按）；
 *  - 手指移动超过 slop：从按下的那一点开始出墨（把瞄准期间的点补上，笔迹不丢）；
 *  - 没移动就抬起：按得很短（≤ tapMs）当作点一下，落一个点；按得久一点当作只是瞄准，什么都不画；
 *  - 瞄准期间第二根手指落下：放弃这一笔（多半是双指拖动 / 缩放）。
 */
class AimGate(private val slopPx: Float, private val tapMs: Long) {

    enum class Phase { IDLE, AIMING, INKING, IGNORED }
    enum class Action {
        /** 吞掉这个事件。 */
        HOLD,
        /** 开始出墨：先补发按下点和瞄准期间攒下的点。 */
        START,
        /** 已在出墨：事件照常交给书写处理。 */
        PASS,
        /** 快速点按：落一个点。 */
        TAP,
        /** 只是瞄准，什么都不画。 */
        DISCARD
    }

    var phase = Phase.IDLE
        private set
    var downX = 0f
        private set
    var downY = 0f
        private set
    private var downT = 0L

    fun active(): Boolean = phase != Phase.IDLE

    fun reset() { phase = Phase.IDLE }

    fun down(x: Float, y: Float, t: Long) {
        phase = Phase.AIMING
        downX = x; downY = y; downT = t
    }

    fun move(x: Float, y: Float): Action = when (phase) {
        Phase.AIMING ->
            if (hypot(x - downX, y - downY) > slopPx) { phase = Phase.INKING; Action.START }
            else Action.HOLD
        Phase.INKING -> Action.PASS
        else -> Action.HOLD
    }

    fun up(t: Long): Action {
        val p = phase
        phase = Phase.IDLE
        return when (p) {
            Phase.AIMING -> if (t - downT <= tapMs) Action.TAP else Action.DISCARD
            Phase.INKING -> Action.PASS
            else -> Action.HOLD
        }
    }

    fun cancel(): Action {
        val p = phase
        phase = Phase.IDLE
        return if (p == Phase.INKING) Action.PASS else Action.DISCARD
    }

    fun secondPointer(): Action = when (phase) {
        Phase.AIMING -> { phase = Phase.IGNORED; Action.DISCARD }
        Phase.INKING -> Action.PASS
        else -> Action.HOLD
    }

    companion object {
        /** 移动超过这么多 dp 才出墨。 */
        const val SLOP_DP = 10f
        /** 按下到抬起不超过这么久，且没移动，算点一下（毫秒）。 */
        const val TAP_MS = 200L
        /** 只瞄准不落笔时，瞄准环抬手后多留这么久（毫秒），方便看清位置。 */
        const val LINGER_MS = 900L
        /** 瞄准期间最多攒多少个事件（只是防止长时间按着抖动无限增长）。 */
        const val MAX_BUFFER = 24
    }
}
