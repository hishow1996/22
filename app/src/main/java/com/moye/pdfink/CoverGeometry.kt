package com.moye.pdfink

import kotlin.math.max
import kotlin.math.min

/** v46：答案遮罩的命中判断，纯 JVM。坐标都是归一化页面坐标，pts = 两个角点。 */
object CoverGeometry {
    /** 圆标圆心离右上角的内缩、圆标半径，都按页面短边的比例。画和点按共用这两个值。 */
    const val INSET_FRAC = 0.022f
    const val RADIUS_FRAC = 0.012f
    /** 新建遮罩的底色（雾蓝灰）。 */
    const val COLOR = 0xFF7C8DB0.toInt()

    /** 规整成 [左, 上, 右, 下]；pts 不足 4 个数返回 null。 */
    fun rect(pts: FloatArray): FloatArray? {
        if (pts.size < 4) return null
        val n = pts.size / 2
        val x0 = pts[0]; val y0 = pts[1]
        val x1 = pts[(n - 1) * 2]; val y1 = pts[(n - 1) * 2 + 1]
        return floatArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }

    fun contains(pts: FloatArray, x: Float, y: Float): Boolean {
        val r = rect(pts) ?: return false
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]
    }

    /** 已揭开的遮罩右上角有个小圆标：圆心离右上角内缩 inset 像素。 */
    fun badgeCenter(pts: FloatArray, w: Float, h: Float, insetPx: Float): FloatArray? {
        val r = rect(pts) ?: return null
        val mx = (r[0] + r[2]) * 0.5f * w
        val my = (r[1] + r[3]) * 0.5f * h
        // 遮罩特别小时，圆标收到中心，免得跑出矩形
        val cx = max(r[2] * w - insetPx, mx)
        val cy = min(r[1] * h + insetPx, my)
        return floatArrayOf(cx, cy)
    }

    /** 点（页面像素）是否落在圆标的点击范围内（半径 hitPx）。 */
    fun badgeHit(
        pts: FloatArray, w: Float, h: Float, insetPx: Float, hitPx: Float,
        px: Float, py: Float
    ): Boolean {
        val c = badgeCenter(pts, w, h, insetPx) ?: return false
        val dx = px - c[0]; val dy = py - c[1]
        return dx * dx + dy * dy <= hitPx * hitPx
    }

    /**
     * 在 marks 里从后往前（最上面的先）找被点中的遮罩下标：
     * - 未揭开：点在矩形内即命中；
     * - 已揭开：只有点在右上角圆标里才命中（别的地方照常写字）。
     * 返回 -1 = 没点到遮罩。
     */
    fun hitIndex(
        marks: List<Mark>, w: Float, h: Float, px: Float, py: Float,
        insetPx: Float, hitPx: Float, isVisible: (Mark) -> Boolean = { true }
    ): Int {
        for (i in marks.indices.reversed()) {
            val m = marks[i]
            if (m.shape != Shapes.COVER || !isVisible(m)) continue
            if (m.revealed) {
                if (badgeHit(m.pts, w, h, insetPx, hitPx, px, py)) return i
            } else if (contains(m.pts, px / w, py / h)) return i
        }
        return -1
    }
}
