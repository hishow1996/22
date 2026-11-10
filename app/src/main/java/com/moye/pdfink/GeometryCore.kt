package com.moye.pdfink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 与 Android 无关的几何核心，便于 JVM 单测直接验证。
 * 坐标约定：erasePx / w / h 为像素，Mark.pts 为归一化坐标。
 */
object GeometryCore {

    fun distToSegment(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 0.0001f) return hypot(px - ax, py - ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / l2
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** 射线法：点是否在闭合多边形内。poly = [x0,y0,x1,y1,...] */
    fun contains(poly: FloatArray, x: Float, y: Float): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y)) {
                val t = (y - yi) / (yj - yi)
                if (x < xi + t * (xj - xi)) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun bounds(poly: FloatArray): FloatArray {
        if (poly.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < poly.size) {
            l = min(l, poly[i]); r = max(r, poly[i])
            t = min(t, poly[i + 1]); b = max(b, poly[i + 1])
            i += 2
        }
        return floatArrayOf(l, t, r, b)
    }

    private fun nearErase(x: Float, y: Float, e: FloatArray, radius: Float): Boolean {
        if (e.isEmpty()) return false
        if (e.size == 2) return hypot(x - e[0], y - e[1]) <= radius
        var i = 2
        while (i + 1 < e.size) {
            if (distToSegment(x, y, e[i - 2], e[i - 1], e[i], e[i + 1]) <= radius) return true
            i += 2
        }
        return false
    }

    /** 一条 mark 的所有采样点是否都落在多边形内。 */
    fun markInside(m: Mark, poly: FloatArray): Boolean {
        val n = m.pts.size / 2
        if (n == 0) return false
        for (i in 0 until n) {
            if (!contains(poly, m.pts[2 * i], m.pts[2 * i + 1])) return false
        }
        return true
    }

    /**
     * 真裁剪：把被橡皮扫到的笔迹从数据里切开，只保留未接触片段。
     * layerFilter 为 false 的层原样保留（隐藏层不应被误擦）。
     */
    fun crop(
        marks: List<Mark>,
        erasePx: FloatArray,
        radiusPx: Float,
        w: Float,
        h: Float,
        layerFilter: (Int) -> Boolean
    ): List<Mark> {
        val out = ArrayList<Mark>(marks.size)
        val unit = min(w, h).coerceAtLeast(1f)
        for (m in marks) {
            if (!layerFilter(m.layer)) {
                out.add(m)
                continue
            }
            val n = m.pts.size / 2
            if (n == 0) continue
            if (m.shape != Shapes.FREE) {
                // 形状命中即整删：橡皮轨迹包围盒（外扩笔宽半径）与形状包围盒相交即删除，
                // 覆盖「橡皮落在形状内部/边线穿过」而顶点不在橡皮附近的情形。
                val mb = bounds(m.pts)
                val mbPx = floatArrayOf(mb[0] * w, mb[1] * h, mb[2] * w, mb[3] * h)
                val eb = eraseBounds(erasePx, radiusPx + m.width * unit * 0.5f)
                val hit = overlaps(mbPx, eb)
                if (!hit) out.add(m)
                continue
            }
            val runPts = ArrayList<Float>()
            val runWs = ArrayList<Float>()
            var touched = false
            for (i in 0 until n) {
                val hit = nearErase(
                    m.pts[2 * i] * w, m.pts[2 * i + 1] * h,
                    erasePx, radiusPx + m.width * unit * 0.5f
                )
                if (hit) {
                    touched = true
                    if (runPts.size >= 2) {
                        out.add(m.copyWith(runPts.toFloatArray(), runWs.toFloatArray()))
                    }
                    runPts.clear(); runWs.clear()
                } else {
                    runPts.add(m.pts[2 * i]); runPts.add(m.pts[2 * i + 1])
                    runWs.add(m.ws?.getOrElse(i) { 1f } ?: 1f)
                }
            }
            if (!touched) out.add(m)
            else if (runPts.size >= 2) {
                out.add(m.copyWith(runPts.toFloatArray(), runWs.toFloatArray()))
            }
        }
        return out
    }

    /** 以 (cx,cy) 为中心缩放，再平移 (dx,dy)，坐标均为归一化。 */
    fun transform(
        m: Mark, cx: Float, cy: Float, dx: Float, dy: Float, scale: Float
    ): Mark {
        val p = FloatArray(m.pts.size)
        var i = 0
        while (i + 1 < p.size) {
            p[i] = (m.pts[i] - cx) * scale + cx + dx
            p[i + 1] = (m.pts[i + 1] - cy) * scale + cy + dy
            i += 2
        }
        return m.copyWith(pts = p, width = (m.width * scale).coerceIn(0.0008f, 0.2f))
    }

    fun markBounds(m: Mark): FloatArray = bounds(m.pts)

    /** 擦除轨迹包围盒（像素），外扩 pad。 */
    fun eraseBounds(erasePx: FloatArray, pad: Float): FloatArray {
        val b = bounds(erasePx)
        if (b[2] < b[0]) return b
        return floatArrayOf(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
    }

    fun overlaps(b: FloatArray, dirty: FloatArray): Boolean =
        b[0] <= dirty[2] && b[2] >= dirty[0] && b[1] <= dirty[3] && b[3] >= dirty[1]

    fun clampRect(b: FloatArray, w: Float, h: Float): FloatArray = floatArrayOf(
        max(0f, min(w, b[0])), max(0f, min(h, b[1])),
        max(0f, min(w, b[2])), max(0f, min(h, b[3]))
    )
}
