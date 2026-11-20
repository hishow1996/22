package com.moye.pdfink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v46：「按住拉直 / 规整图形」的识别。纯 JVM。
 * 输入是一笔自由笔迹（归一化 x,y 交错），输出直线或椭圆；看不出来就返回 null，
 * 宁可不吸附也不要把手写的字吸成形状。
 */
object ShapeSnap {

    sealed class Result {
        /** 归一化起点 / 终点。 */
        class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Result()
        /** 归一化外接矩形（与 Shapes.OVAL 的两个角点同口径）。 */
        class Oval(val l: Float, val t: Float, val r: Float, val b: Float) : Result()
        /** v49：横平竖直的长方形 / 正方形，归一化外接矩形（与 Shapes.RECT 的两个角点同口径）。 */
        class Rect(val l: Float, val t: Float, val r: Float, val b: Float) : Result()
        /**
         * v49：闭合多边形（三角形、斜着的四边形 / 菱形 / 梯形），归一化顶点 x,y 交错、按环绕顺序，
         * 不重复首点。要落成笔迹时用 [polygonStroke] 加密成折线。
         */
        class Poly(val pts: FloatArray) : Result()
    }

    /** 太短的不识别（像素）。 */
    const val MIN_SIZE_PX = 40f
    /** 闭合图形最短那条边至少多长（像素），太扁的多半是来回描的线。 */
    private const val MIN_SIDE_PX = 28f
    private const val RESAMPLE = 160

    /** 直线判定时依次试的「两头各去掉多少比例」：整笔 → 去掉小勾 → 只去头 / 只去尾。 */
    private val LINE_TRIMS = arrayOf(
        floatArrayOf(0f, 0f), floatArrayOf(0.07f, 0.07f), floatArrayOf(0.14f, 0.14f),
        floatArrayOf(0f, 0.12f), floatArrayOf(0.12f, 0f)
    )

    /**
     * 点列（像素）去掉头 trimHead / 尾 trimTail 比例后像不像一条直线：弦长 ≥ [MIN_SIZE_PX]，
     * 弦长占路径长 ≥ 0.80，所有点离弦不超过弦长的 14%。像就返回 x0,y0,x1,y1（像素），不像返回 null。
     */
    private fun lineOf(xs: FloatArray, ys: FloatArray, trimHead: Float, trimTail: Float): FloatArray? {
        val n = xs.size
        val a = (n * trimHead).toInt()
        val b = n - 1 - (n * trimTail).toInt()
        if (b - a < 7) return null
        var pathLen = 0f
        for (i in a + 1..b) pathLen += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
        val chord = hypot(dx, dy)
        if (chord < MIN_SIZE_PX || chord / pathLen < 0.80f) return null
        var maxDev = 0f
        for (i in a..b) {
            val dev = abs(dx * (ys[i] - ys[a]) - dy * (xs[i] - xs[a])) / chord
            if (dev > maxDev) maxDev = dev
        }
        if (maxDev > 0.14f * chord) return null
        return floatArrayOf(xs[a], ys[a], xs[b], ys[b])
    }

    fun detect(pts: FloatArray, w: Float, h: Float): Result? {
        val n = pts.size / 2
        if (n < 8 || w <= 0f || h <= 0f) return null
        val xs = FloatArray(n) { pts[2 * it] * w }
        val ys = FloatArray(n) { pts[2 * it + 1] * h }

        var pathLen = 0f
        var minX = xs[0]; var maxX = xs[0]; var minY = ys[0]; var maxY = ys[0]
        for (i in 1 until n) {
            pathLen += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }
        if (pathLen < MIN_SIZE_PX) return null
        val bw = maxX - minX
        val bh = maxY - minY
        val chord = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])

        // ---- 直线：弦长接近路径长，且所有点离弦都不远（手画的线常常有点弯、两头有小勾，所以放宽，
        //      整笔不行再去掉两头一小段试一次）----
        for (trim in LINE_TRIMS) {
            val ln = lineOf(xs, ys, trim[0], trim[1]) ?: continue
            var x0 = ln[0]; var y0 = ln[1]; var x1 = ln[2]; var y1 = ln[3]
            val dx = x1 - x0; val dy = y1 - y0
            // 接近水平 / 竖直（±4°）就摆正，保持中点不动
            val ang = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble()))
            if (ang <= 4.0) {
                val cy = (y0 + y1) / 2f; y0 = cy; y1 = cy
            } else if (ang >= 86.0) {
                val cx = (x0 + x1) / 2f; x0 = cx; x1 = cx
            }
            return Result.Line(x0 / w, y0 / h, x1 / w, y1 / h)
        }

        // ---- 闭合图形：椭圆 / 三角形 / 四边形（含长方形、正方形）----
        val big = max(bw, bh)
        val small = min(bw, bh)
        if (big < MIN_SIZE_PX || small < MIN_SIDE_PX) return null
        // 基本闭合：首尾距离不超过图形尺寸的 40%、也不超过路径长的 22%（手画常常没封口或有点出头）
        if (chord > 0.25f * pathLen) return null

        val rx = FloatArray(RESAMPLE)
        val ry = FloatArray(RESAMPLE)
        resample(xs, ys, pathLen, rx, ry)

        // 绕圈角度：绕笔迹重心逐点展开（重心一定在凸图形里面，三角形 / 斜着的长条也成立），
        // 必须接近一整圈且基本单向（排除来回描、只画了半个）
        var gx = 0f; var gy = 0f
        for (i in 0 until RESAMPLE) { gx += rx[i]; gy += ry[i] }
        gx /= RESAMPLE; gy /= RESAMPLE
        var sweep = 0.0
        var prev = atan2((ry[0] - gy).toDouble(), (rx[0] - gx).toDouble())
        for (i in 1 until RESAMPLE) {
            val cur = atan2((ry[i] - gy).toDouble(), (rx[i] - gx).toDouble())
            var d = cur - prev
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            sweep += d
            prev = cur
        }
        if (abs(sweep) < 2 * PI * 0.78) return null

        val scale = sqrt(bw * bh)                       // 图形的「大小」，各模型的误差都除以它再比较

        // 「有没有明显拐角」比拟合误差更能分开圆和多边形：圆各处弯得差不多，
        // 三角形 / 四边形的边是直的、弯都集中在几个拐角上。straight = 近似直线段占的比例。
        val straight = straightFraction(rx, ry)
        val curvy = straight < 0.20f

        // 椭圆：先按横竖摆正拟合；再按笔迹自己的主轴方向拟合（斜着画的椭圆），明显更贴就用斜的
        val axisFit = fitEllipse(rx, ry, 0.0)
        var oval = axisFit
        val pca = principalAngle(rx, ry)
        val tiltDeg = abs(((Math.toDegrees(pca) % 90.0) + 90.0) % 90.0).let { if (it > 45.0) 90.0 - it else it }
        if (tiltDeg >= 8.0) {
            val tf = fitEllipse(rx, ry, pca)
            val ratio = min(tf.a, tf.b) / max(tf.a, tf.b)
            if (tf.mean < 0.75f * axisFit.mean && ratio < 0.88f) oval = tf
        }
        val ovalOk = oval.mean <= (if (curvy) 0.26f else 0.20f) && oval.p90 <= (if (curvy) 0.50f else 0.38f)

        val tri = fitPolygon(rx, ry, 3, scale)
        val quad = fitPolygon(rx, ry, 4, scale)
        val triOk = tri != null && tri.mean <= 0.07f && tri.p90 <= 0.14f
        val quadOk = quad != null && quad.mean <= 0.07f && quad.p90 <= 0.14f

        // 三角形：四边形模型多一个顶点、误差总会略小，所以三角形误差在 1.5 倍以内就认三角形
        val polyChoice: PolyFit? = when {
            triOk && quadOk -> if (tri!!.mean <= 1.5f * quad!!.mean) tri else quad
            triOk -> tri
            quadOk -> quad
            else -> null
        }
        // 有明显拐角（直边占三成以上）→ 多边形；否则像椭圆就是椭圆；
        // 都不像椭圆、但直边也不算少（二成五以上）且拟合得上多边形，才退而认多边形。
        // 多边形的判据：直边占三成以上；或者拟合得非常贴（圆被四边形拟合的误差天生在 0.02 以上，
        // 真多边形一般在 0.015 以下）且直边不算太少。
        val polyEvidence = polyChoice != null &&
            (straight >= 0.30f || (polyChoice.mean <= 0.017f && straight >= 0.15f))
        if (polyEvidence) return polygonResult(polyChoice!!, w, h)
        if (ovalOk) return ovalResult(oval, axisFit, w, h)
        if (polyChoice != null && straight >= 0.25f) return polygonResult(polyChoice, w, h)
        return null
    }

    // ---------- 椭圆拟合 / 直边占比 ----------

    /** 椭圆拟合：中心 (cx,cy)（像素）、半轴 a（th 方向）b、方向 th（弧度）、径向偏差均值 / p90。 */
    private class OvalFit(
        val cx: Float, val cy: Float, val a: Float, val b: Float, val th: Double,
        val mean: Float, val p90: Float
    )

    private fun fitEllipse(rx: FloatArray, ry: FloatArray, th: Double): OvalFit {
        val n = rx.size
        val c = kotlin.math.cos(th).toFloat(); val sn = kotlin.math.sin(th).toFloat()
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += rx[i]; my += ry[i] }
        mx /= n; my /= n
        val us = FloatArray(n); val vs = FloatArray(n)
        var umin = Float.MAX_VALUE; var umax = -Float.MAX_VALUE
        var vmin = Float.MAX_VALUE; var vmax = -Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = rx[i] - mx; val dy = ry[i] - my
            us[i] = dx * c + dy * sn
            vs[i] = -dx * sn + dy * c
            umin = min(umin, us[i]); umax = max(umax, us[i])
            vmin = min(vmin, vs[i]); vmax = max(vmax, vs[i])
        }
        val cu = (umin + umax) / 2f; val cv = (vmin + vmax) / 2f
        val a = ((umax - umin) / 2f).coerceAtLeast(1e-3f)
        val b = ((vmax - vmin) / 2f).coerceAtLeast(1e-3f)
        val devs = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val ux = (us[i] - cu) / a; val uy = (vs[i] - cv) / b
            devs[i] = abs(sqrt(ux * ux + uy * uy) - 1f)
            sum += devs[i]
        }
        devs.sort()
        val cxp = mx + cu * c - cv * sn
        val cyp = my + cu * sn + cv * c
        return OvalFit(cxp, cyp, a, b, th, sum / n, devs[(n * 0.9f).toInt().coerceAtMost(n - 1)])
    }

    /** 点云的主轴方向（弧度）：椭圆均匀取点时，它就是椭圆长 / 短轴方向。 */
    private fun principalAngle(rx: FloatArray, ry: FloatArray): Double {
        val n = rx.size
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += rx[i]; my += ry[i] }
        mx /= n; my /= n
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until n) {
            val dx = rx[i] - mx; val dy = ry[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        return 0.5 * atan2((2f * sxy).toDouble(), (sxx - syy).toDouble())
    }

    private fun ovalResult(o: OvalFit, axisFit: OvalFit, w: Float, h: Float): Result {
        if (o === axisFit) {
            val l = o.cx - o.a; val r = o.cx + o.a
            val t = o.cy - o.b; val b = o.cy + o.b
            // 长宽相差不到 12% 当正圆，取均值，中心不动
            val bw = r - l; val bh = b - t
            if (abs(bw - bh) <= 0.12f * max(bw, bh)) {
                val sd = (bw + bh) / 2f
                return Result.Oval((o.cx - sd / 2f) / w, (o.cy - sd / 2f) / h, (o.cx + sd / 2f) / w, (o.cy + sd / 2f) / h)
            }
            return Result.Oval(l / w, t / h, r / w, b / h)
        }
        // 斜着的椭圆：外接矩形的椭圆画不出倾斜，所以给一圈等距的点（走多边形那条路落成加密折线，看上去就是光滑椭圆）
        val m = 72
        val out = FloatArray(2 * m)
        val c = kotlin.math.cos(o.th); val sn = kotlin.math.sin(o.th)
        for (i in 0 until m) {
            val t = 2 * PI * i / m
            val u = o.a * kotlin.math.cos(t); val v = o.b * kotlin.math.sin(t)
            out[2 * i] = ((o.cx + u * c - v * sn) / w).toFloat()
            out[2 * i + 1] = ((o.cy + u * sn + v * c) / h).toFloat()
        }
        return Result.Poly(out)
    }

    /**
     * 近似直线段占的比例：先轻微平滑，再看每个点前后一小段的转角，转角小于平均转角 30% 的算「直」。
     * 圆几乎处处在转（接近 0）；长方形 / 三角形的边是直的，一般在 0.4~0.7 之间。
     */
    private fun straightFraction(rx: FloatArray, ry: FloatArray): Float {
        val n = rx.size
        val sx = FloatArray(n); val sy = FloatArray(n)
        for (i in 0 until n) {
            var ax = 0f; var ay = 0f; var c = 0
            for (d in -2..2) { val k = i + d; if (k in 0 until n) { ax += rx[k]; ay += ry[k]; c++ } }
            sx[i] = ax / c; sy[i] = ay / c
        }
        val span = 7
        val turns = ArrayList<Double>()
        for (i in span until n - span) {
            val ax = sx[i] - sx[i - span]; val ay = sy[i] - sy[i - span]
            val bx = sx[i + span] - sx[i]; val by = sy[i + span] - sy[i]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-3f || lb < 1e-3f) { turns.add(0.0); continue }
            val cosv = ((ax * bx + ay * by) / (la * lb)).toDouble().coerceIn(-1.0, 1.0)
            turns.add(Math.toDegrees(kotlin.math.acos(cosv)))
        }
        if (turns.isEmpty()) return 0f
        val mean = turns.average()
        if (mean < 1e-3) return 1f
        val low = 0.3 * mean
        return turns.count { it < low }.toFloat() / turns.size
    }

    // ---------- 多边形拟合 ----------

    /** 顶点 v（x,y 交错，像素，按环绕顺序）及其对点列的拟合误差（已除以图形大小）。 */
    private class PolyFit(val v: FloatArray, val mean: Float, val p90: Float)

    private fun resample(xs: FloatArray, ys: FloatArray, pathLen: Float, ox: FloatArray, oy: FloatArray) {
        val m = ox.size
        val step = pathLen / (m - 1)
        ox[0] = xs[0]; oy[0] = ys[0]
        var seg = 1
        var acc = 0f                                    // 已走过的前面各段总长
        var segLen = hypot(xs[1] - xs[0], ys[1] - ys[0])
        for (k in 1 until m) {
            val target = step * k
            while (seg < xs.size - 1 && acc + segLen < target) {
                acc += segLen
                seg++
                segLen = hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
            }
            val tt = if (segLen > 1e-6f) ((target - acc) / segLen).coerceIn(0f, 1f) else 0f
            ox[k] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * tt
            oy[k] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * tt
        }
    }

    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    private fun fitPolygon(rx: FloatArray, ry: FloatArray, k: Int, scale: Float): PolyFit? {
        val n = rx.size
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += rx[i]; my += ry[i] }
        mx /= n; my /= n

        fun far(fx: Float, fy: Float): Int {
            var bi = 0; var bd = -1f
            for (i in 0 until n) {
                val d = hypot(rx[i] - fx, ry[i] - fy)
                if (d > bd) { bd = d; bi = i }
            }
            return bi
        }
        // 到直线 AB 的有符号距离最大 / 最小的点
        fun extremeSide(ai: Int, bi: Int, positive: Boolean): Int {
            val dx = rx[bi] - rx[ai]; val dy = ry[bi] - ry[ai]
            val len = hypot(dx, dy).coerceAtLeast(1e-3f)
            var best = -1; var bd = 0f
            for (i in 0 until n) {
                val sd = (dx * (ry[i] - ry[ai]) - dy * (rx[i] - rx[ai])) / len
                val v = if (positive) sd else -sd
                if (v > bd) { bd = v; best = i }
            }
            return best
        }

        // 初始顶点：离重心最远的点 A、离 A 最远的点 B，再取离 AB 线最远的点（三角形一个，四边形两侧各一个）
        val ai = far(mx, my)
        val bi = far(rx[ai], ry[ai])
        val idx = ArrayList<Int>()
        idx.add(ai); idx.add(bi)
        if (k == 3) {
            val p = extremeSide(ai, bi, true)
            val q = extremeSide(ai, bi, false)
            val dp = if (p >= 0) distLine(rx, ry, p, ai, bi) else 0f
            val dq = if (q >= 0) distLine(rx, ry, q, ai, bi) else 0f
            val c = if (dp >= dq) p else q
            if (c < 0) return null
            idx.add(c)
        } else {
            val p = extremeSide(ai, bi, true)
            val q = extremeSide(ai, bi, false)
            if (p < 0 || q < 0) return null
            idx.add(p); idx.add(q)
        }
        var v = FloatArray(2 * k)
        // 按绕重心的角度排成环
        val order = idx.sortedBy { atan2((ry[it] - my).toDouble(), (rx[it] - mx).toDouble()) }
        for ((j, id) in order.withIndex()) { v[2 * j] = rx[id]; v[2 * j + 1] = ry[id] }

        // 迭代精修：每个点归到最近的边，用各边的点拟合直线，相邻直线求交得新顶点
        var counts = IntArray(k)
        repeat(3) {
            counts = IntArray(k)
            val sx = FloatArray(k); val sy = FloatArray(k)
            val sxx = FloatArray(k); val sxy = FloatArray(k); val syy = FloatArray(k)
            val owner = IntArray(n)
            for (i in 0 until n) {
                var bj = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) {
                    val nj = (j + 1) % k
                    val d = distToSeg(rx[i], ry[i], v[2 * j], v[2 * j + 1], v[2 * nj], v[2 * nj + 1])
                    if (d < bd) { bd = d; bj = j }
                }
                owner[i] = bj
                counts[bj]++
                sx[bj] += rx[i]; sy[bj] += ry[i]
            }
            val lx = FloatArray(k); val ly = FloatArray(k); val dxs = FloatArray(k); val dys = FloatArray(k)
            for (j in 0 until k) {
                if (counts[j] < 3) return null          // 有一条边几乎没有笔迹：不是这个多边形
                lx[j] = sx[j] / counts[j]; ly[j] = sy[j] / counts[j]
            }
            for (i in 0 until n) {
                val j = owner[i]
                val ex = rx[i] - lx[j]; val ey = ry[i] - ly[j]
                sxx[j] += ex * ex; sxy[j] += ex * ey; syy[j] += ey * ey
            }
            for (j in 0 until k) {
                val th = 0.5 * atan2((2f * sxy[j]).toDouble(), (sxx[j] - syy[j]).toDouble())
                dxs[j] = kotlin.math.cos(th).toFloat(); dys[j] = kotlin.math.sin(th).toFloat()
            }
            val nv = v.copyOf()
            for (j in 0 until k) {
                // 顶点 j 是第 j-1 条边与第 j 条边的交点
                val pj = (j + k - 1) % k
                val den = dxs[pj] * dys[j] - dys[pj] * dxs[j]
                if (abs(den) < 0.2f) continue           // 两边几乎平行，交点不可靠，保留原顶点
                val t = ((lx[j] - lx[pj]) * dys[j] - (ly[j] - ly[pj]) * dxs[j]) / den
                val ix = lx[pj] + dxs[pj] * t
                val iy = ly[pj] + dys[pj] * t
                if (hypot(ix - mx, iy - my) > 2.2f * scale) continue
                nv[2 * j] = ix; nv[2 * j + 1] = iy
            }
            v = nv
        }

        // 形状合理性：凸、内角不能太尖或太平
        if (!convexWithAngles(v, k)) return null
        // 每条边都要有足够的笔迹（不能一条边只占零星几个点）。「够」按边的长短算：
        // 细长的长方形短边本来就只占路径的几 %，不能用统一比例卡死它。
        var perim = 0f
        val edgeLen = FloatArray(k)
        for (j in 0 until k) {
            val nj = (j + 1) % k
            edgeLen[j] = hypot(v[2 * nj] - v[2 * j], v[2 * nj + 1] - v[2 * j + 1])
            perim += edgeLen[j]
        }
        if (perim < 1e-3f) return null
        for (j in 0 until k) {
            val expect = edgeLen[j] / perim
            if (counts[j] < 3 || counts[j].toFloat() / n < 0.45f * expect) return null
        }

        val ds = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            var bd = Float.MAX_VALUE
            for (j in 0 until k) {
                val nj = (j + 1) % k
                bd = min(bd, distToSeg(rx[i], ry[i], v[2 * j], v[2 * j + 1], v[2 * nj], v[2 * nj + 1]))
            }
            ds[i] = bd / scale
            sum += ds[i]
        }
        ds.sort()
        return PolyFit(v, sum / n, ds[(n * 0.9f).toInt().coerceAtMost(n - 1)])
    }

    private fun distLine(rx: FloatArray, ry: FloatArray, i: Int, ai: Int, bi: Int): Float {
        val dx = rx[bi] - rx[ai]; val dy = ry[bi] - ry[ai]
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        return abs(dx * (ry[i] - ry[ai]) - dy * (rx[i] - rx[ai])) / len
    }

    /** 凸多边形，且内角在 [minDeg, maxDeg] 内（三角形 12°..150°，四边形 35°..155°）。 */
    private fun convexWithAngles(v: FloatArray, k: Int): Boolean {
        var sign = 0
        val minDeg = if (k == 3) 12.0 else 35.0
        val maxDeg = if (k == 3) 150.0 else 155.0
        for (j in 0 until k) {
            val p = (j + k - 1) % k
            val nx = (j + 1) % k
            val ax = v[2 * j] - v[2 * p]; val ay = v[2 * j + 1] - v[2 * p + 1]
            val bx = v[2 * nx] - v[2 * j]; val by = v[2 * nx + 1] - v[2 * j + 1]
            val cross = ax * by - ay * bx
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-3f || lb < 1e-3f) return false
            val cosv = ((-ax) * bx + (-ay) * by) / (la * lb)          // 顶点 j 处两边夹角的余弦
            val deg = Math.toDegrees(kotlin.math.acos(cosv.toDouble().coerceIn(-1.0, 1.0)))
            if (deg < minDeg || deg > maxDeg) return false
        }
        return true
    }

    /** 四边形若四条边都在 ±16° 内横平竖直、四个角都近似直角（手画不用多规整） → 摆正成长方形（长宽接近则取正方形）；否则保留成多边形。 */
    private fun polygonResult(f: PolyFit, w: Float, h: Float): Result {
        val v = f.v
        val k = v.size / 2
        if (k == 4) {
            var axisOk = true
            var rightOk = true
            for (j in 0 until 4) {
                val nx = (j + 1) % 4
                val p = (j + 3) % 4
                val ex = v[2 * nx] - v[2 * j]; val ey = v[2 * nx + 1] - v[2 * j + 1]
                val ang = Math.toDegrees(atan2(ey.toDouble(), ex.toDouble()))
                val dev = abs(((ang % 90.0) + 135.0) % 90.0 - 45.0)                          // 到最近坐标轴的角度（0..45）
                if (dev > 20.0) axisOk = false
                val ax = v[2 * j] - v[2 * p]; val ay = v[2 * j + 1] - v[2 * p + 1]
                val la = hypot(ax, ay); val lb = hypot(ex, ey)
                val cosv = ((-ax) * ex + (-ay) * ey) / (la * lb)
                val deg = Math.toDegrees(kotlin.math.acos(cosv.toDouble().coerceIn(-1.0, 1.0)))
                if (abs(deg - 90.0) > 28.0) rightOk = false
            }
            if (axisOk && rightOk) {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (j in 0 until 4) {
                    l = min(l, v[2 * j]); r = max(r, v[2 * j])
                    t = min(t, v[2 * j + 1]); b = max(b, v[2 * j + 1])
                }
                val bw = r - l; val bh = b - t
                if (abs(bw - bh) <= 0.12f * max(bw, bh)) {     // 长宽相差不到 12% 当正方形，中心不动
                    val s = (bw + bh) / 2f
                    val cx = (l + r) / 2f; val cy = (t + b) / 2f
                    l = cx - s / 2f; r = cx + s / 2f; t = cy - s / 2f; b = cy + s / 2f
                }
                return Result.Rect(l / w, t / h, r / w, b / h)
            }
        }
        val out = FloatArray(v.size)
        for (j in 0 until k) { out[2 * j] = v[2 * j] / w; out[2 * j + 1] = v[2 * j + 1] / h }
        return Result.Poly(out)
    }

    /**
     * 把闭合多边形顶点（归一化）加密成自由笔迹折线：沿每条边每隔 [stepPx] 像素一个点，首尾相接。
     * 加密后再走普通笔迹的平滑，转角只会被圆掉几个像素，看上去仍是尖角。
     */
    fun polygonStroke(poly: FloatArray, w: Float, h: Float, stepPx: Float = 4f): FloatArray {
        val k = poly.size / 2
        if (k < 2) return poly.copyOf()
        val out = ArrayList<Float>()
        for (j in 0 until k) {
            val nj = (j + 1) % k
            val x0 = poly[2 * j] * w; val y0 = poly[2 * j + 1] * h
            val x1 = poly[2 * nj] * w; val y1 = poly[2 * nj + 1] * h
            val len = hypot(x1 - x0, y1 - y0)
            val steps = max(1, (len / stepPx).toInt())
            for (s in 0 until steps) {
                val t = s / steps.toFloat()
                out.add((x0 + (x1 - x0) * t) / w); out.add((y0 + (y1 - y0) * t) / h)
            }
        }
        out.add(poly[0]); out.add(poly[1])               // 封口
        return out.toFloatArray()
    }
}
