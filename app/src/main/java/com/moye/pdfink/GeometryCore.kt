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
            // 先用包围盒挡掉绝大多数没碰到的笔迹：橡皮轨迹长、笔迹多时，
            // 逐点对逐段的距离计算是平方级，抬手会明显卡一下。
            val reach = radiusPx + m.width * unit * 0.5f
            val box = eraseBounds(erasePx, reach)
            if (box[2] >= box[0]) {
                val mb = bounds(m.pts)
                val mbPx = floatArrayOf(mb[0] * w, mb[1] * h, mb[2] * w, mb[3] * h)
                if (!overlaps(mbPx, box)) {
                    out.add(m)
                    continue
                }
            }
            val runPts = ArrayList<Float>()
            val runWs = ArrayList<Float>()
            var touched = false
            for (i in 0 until n) {
                val px = m.pts[2 * i] * w
                val py = m.pts[2 * i + 1] * h
                // 点在轨迹包围盒（已外扩 reach）之外，必然没被擦到
                val hit = px >= box[0] && px <= box[2] && py >= box[1] && py <= box[3] &&
                    nearErase(px, py, erasePx, reach)
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

    // ---------- v41：套索命中（「碰到就中」） ----------
    // 坐标仍为归一化 [0,1]；padX / padY 是该方向上的容差（归一化），
    // 用各向异性距离判定，避免 width≠height 时容差被拉伸。
    // 相交/内外判定在仿射变换下不变，可直接用归一化坐标；只有「距离」需要 sx/sy 缩放。

    /** 严格叉积相交，外加端点/共线接触兜底（EPS 极小，容差交给 pad 层）。 */
    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ): Boolean {
        fun cross(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) =
            (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = cross(ax, ay, bx, by, cx, cy)
        val d2 = cross(ax, ay, bx, by, dx, dy)
        val d3 = cross(cx, cy, dx, dy, ax, ay)
        val d4 = cross(cx, cy, dx, dy, bx, by)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        ) return true
        val eps = 0.000001f
        return distToSegment(cx, cy, ax, ay, bx, by) <= eps ||
            distToSegment(dx, dy, ax, ay, bx, by) <= eps ||
            distToSegment(ax, ay, cx, cy, dx, dy) <= eps ||
            distToSegment(bx, by, cx, cy, dx, dy) <= eps
    }

    /** 点在缩放后的空间里到线段的距离；sx=1/padX, sy=1/padY，返回值 ≤1 即落在容差内。 */
    private fun distAniso(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float,
        sx: Float, sy: Float
    ): Float {
        val dx = (bx - ax) * sx
        val dy = (by - ay) * sy
        val l2 = dx * dx + dy * dy
        if (l2 < 0.000000000001f) return hypot((px - ax) * sx, (py - ay) * sy)
        var t = ((px - ax) * sx * dx + (py - ay) * sy * dy) / l2
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        return hypot((px - ax - t * (bx - ax)) * sx, (py - ay - t * (by - ay)) * sy)
    }

    /** 两条线段相交，或最短距离在容差内。 */
    private fun nearSegments(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        sx: Float, sy: Float
    ): Boolean {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return true
        return minOf(
            distAniso(ax, ay, cx, cy, dx, dy, sx, sy),
            distAniso(bx, by, cx, cy, dx, dy, sx, sy),
            distAniso(cx, cy, ax, ay, bx, by, sx, sy),
            distAniso(dx, dy, ax, ay, bx, by, sx, sy)
        ) <= 1f
    }

    /** 遍历闭合多边形的每条边（含首末补的那条弦）。 */
    private fun anyEdge(poly: FloatArray, pred: (Float, Float, Float, Float) -> Boolean): Boolean {
        val n = poly.size / 2
        if (n < 2) return false
        var j = n - 1
        for (i in 0 until n) {
            if (pred(poly[2 * j], poly[2 * j + 1], poly[2 * i], poly[2 * i + 1])) return true
            j = i
        }
        return false
    }

    /** 点在圈内，或贴着圈的边（压线算进）。 */
    private fun polyNearPoint(
        poly: FloatArray, x: Float, y: Float, sx: Float, sy: Float
    ): Boolean {
        if (contains(poly, x, y)) return true
        return anyEdge(poly) { ax, ay, bx, by ->
            distAniso(x, y, ax, ay, bx, by, sx, sy) <= 1f
        }
    }

    /** 线段穿过圈，或贴着圈的边。 */
    private fun polyHitsSegment(
        poly: FloatArray, ax: Float, ay: Float, bx: Float, by: Float,
        sx: Float, sy: Float
    ): Boolean = anyEdge(poly) { cx, cy, dx, dy ->
        nearSegments(ax, ay, bx, by, cx, cy, dx, dy, sx, sy)
    }

    /** 形状/长笔画：按包围盒矩形与圈相交判，与橡皮同口径。 */
    private fun rectHitsPoly(
        b: FloatArray, poly: FloatArray, sx: Float, sy: Float,
        padX: Float, padY: Float
    ): Boolean {
        val l = b[0]; val t = b[1]; val r = b[2]; val bo = b[3]
        // 矩形四角在圈内 / 贴边
        if (polyNearPoint(poly, l, t, sx, sy) || polyNearPoint(poly, r, t, sx, sy) ||
            polyNearPoint(poly, r, bo, sx, sy) || polyNearPoint(poly, l, bo, sx, sy)
        ) return true
        // 矩形四边穿过圈 / 贴边
        if (polyHitsSegment(poly, l, t, r, t, sx, sy) ||
            polyHitsSegment(poly, r, t, r, bo, sx, sy) ||
            polyHitsSegment(poly, r, bo, l, bo, sx, sy) ||
            polyHitsSegment(poly, l, bo, l, t, sx, sy)
        ) return true
        // 圈整体落在矩形内部（圈画得比形状小）
        val n = poly.size / 2
        for (i in 0 until n) {
            val x = poly[2 * i]; val y = poly[2 * i + 1]
            if (x >= l - padX && x <= r + padX && y >= t - padY && y <= bo + padY) return true
        }
        return false
    }

    /**
     * 圈采样点不足时补成合法多边形，让「快划一下 / 点一下」也能选中附近笔迹。
     * ≥3 点原样返回（射线法与 drawPath.close() 天然闭合，首末补弦一致）；
     * 2 点扩成沿法线外扩的胶囊矩形；1 点扩成小方框。
     */
    fun lassoHitPolygon(raw: FloatArray, padX: Float, padY: Float): FloatArray {
        if (raw.size >= 6) return raw
        if (raw.size == 4) {
            val ax = raw[0]; val ay = raw[1]; val bx = raw[2]; val by = raw[3]
            val dx = bx - ax; val dy = by - ay
            val len = hypot(dx, dy)
            if (len > 0.000001f) {
                val nx = -dy / len * padX
                val ny = dx / len * padY
                return floatArrayOf(
                    ax + nx, ay + ny, bx + nx, by + ny,
                    bx - nx, by - ny, ax - nx, ay - ny
                )
            }
            return floatArrayOf(
                ax - padX, ay - padY, ax + padX, ay - padY,
                ax + padX, ay + padY, ax - padX, ay + padY
            )
        }
        if (raw.size == 2) {
            val x = raw[0]; val y = raw[1]
            return floatArrayOf(
                x - padX, y - padY, x + padX, y - padY,
                x + padX, y + padY, x - padX, y + padY
            )
        }
        return raw
    }

    /**
     * v41 套索主判定：从「所有采样点都在圈内」改为「碰到就中」。
     * ① 包围盒粗筛（笔迹多时不退化成 O(n·m)）② 点在内 / 压线
     * ③ 相邻采样点连成的线段穿过圈 ④ 形状按包围盒相交。
     */
    fun markHitsLasso(m: Mark, poly: FloatArray, padX: Float, padY: Float): Boolean {
        if (poly.size < 6 || m.pts.isEmpty()) return false
        val pb = bounds(poly)
        val mb = bounds(m.pts)
        val padded = floatArrayOf(
            pb[0] - padX, pb[1] - padY, pb[2] + padX, pb[3] + padY
        )
        if (!overlaps(mb, padded)) return false
        val sx = 1f / padX.coerceAtLeast(0.000001f)
        val sy = 1f / padY.coerceAtLeast(0.000001f)
        if (m.shape != Shapes.FREE) return rectHitsPoly(mb, poly, sx, sy, padX, padY)
        val n = m.pts.size / 2
        for (i in 0 until n) {
            if (polyNearPoint(poly, m.pts[2 * i], m.pts[2 * i + 1], sx, sy)) return true
        }
        var i = 1
        while (i < n) {
            if (polyHitsSegment(
                    poly, m.pts[2 * i - 2], m.pts[2 * i - 1],
                    m.pts[2 * i], m.pts[2 * i + 1], sx, sy
                )
            ) return true
            i++
        }
        return false
    }
}
