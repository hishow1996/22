package com.moye.pdfink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * 稀疏分块位图层，InkView 的「已落笔」和「当前笔」两层共用。
 *
 * 原来每层是一张整页 ARGB_8888 位图。横屏整页动辄 2300×3000 以上，单张近 30MB：
 * 硬件加速下，位图只要被改过一个像素，下一帧就要整张重新上传 GPU，所以写字延迟、
 * 滑动时显存也被两张大图占满。
 *
 * 现在按 [InkTileMath.TILE] 切块，只有真正画了东西的块才分配位图：
 * - 写一笔只会改到几块小图，重传量是原来的百分之几；
 * - 没有笔迹的页面不占位图内存；
 * - 画法不变：同一批绘制指令对每块各执行一次（画布平移 + 裁剪到块内），
 *   每个像素仍然只被画一次，结果与整页位图逐像素一致。
 */
class InkLayer {

    private var w = 0
    private var h = 0
    private var cols = 0
    private var rows = 0
    private var tiles: Array<Bitmap?> = arrayOfNulls(0)
    private var count = 0

    /** 所有块共用一个软件画布，每次作画前 setBitmap 到目标块。 */
    private val canvas = Canvas()
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** 已按有效尺寸建好网格。释放后为 false，需要重新 [resize] 并重画。 */
    val ready: Boolean get() = w > 0 && h > 0

    fun matches(width: Int, height: Int): Boolean = ready && w == width && h == height

    /** 换尺寸：丢弃旧块、建新网格；内容为空，调用方负责重画。 */
    fun resize(width: Int, height: Int) {
        release()
        if (width <= 0 || height <= 0) return
        w = width
        h = height
        cols = InkTileMath.count(width)
        rows = InkTileMath.count(height)
        tiles = arrayOfNulls(cols * rows)
    }

    /** 丢弃全部块并回到未就绪状态（低内存 / 尺寸变化）。 */
    fun release() {
        clear()
        w = 0
        h = 0
        cols = 0
        rows = 0
        tiles = arrayOfNulls(0)
    }

    /** 丢弃全部块（释放内存），网格尺寸保留。 */
    fun clear() {
        if (count == 0) return
        for (i in tiles.indices) {
            tiles[i]?.recycle()
            tiles[i] = null
        }
        count = 0
    }

    /** 只擦掉所有已分配块的像素、不释放块，用于套索 / 选区这类每帧重画的预览。 */
    fun wipe() {
        if (count == 0) return
        for (t in tiles) t?.eraseColor(Color.TRANSPARENT)
    }

    /**
     * 对与 [left, top, right, bottom]（层坐标，像素）相交的每一块执行 [block]。
     * block 收到的画布已平移成层坐标，直接按整页像素坐标作画即可。
     *
     * @param create false 时只处理已存在的块，不为空白区域分配位图（擦除用）
     * @param clip true 时再把作画范围裁到该矩形内（局部重绘用）
     */
    fun paint(
        left: Float, top: Float, right: Float, bottom: Float,
        create: Boolean = true,
        clip: Boolean = false,
        block: (Canvas) -> Unit
    ) {
        if (!ready) return
        val colRange = InkTileMath.range(left, right, cols)
        val rowRange = InkTileMath.range(top, bottom, rows)
        if (colRange.isEmpty() || rowRange.isEmpty()) return
        try {
            for (r in rowRange) {
                for (c in colRange) {
                    val index = r * cols + c
                    var bitmap = tiles[index]
                    if (bitmap == null) {
                        if (!create) continue
                        bitmap = Bitmap.createBitmap(
                            InkTileMath.tileSize(c, w), InkTileMath.tileSize(r, h),
                            Bitmap.Config.ARGB_8888
                        )
                        tiles[index] = bitmap
                        count++
                    }
                    // setBitmap 会重置矩阵 / 裁剪 / save 栈，每块都从干净状态开始
                    canvas.setBitmap(bitmap)
                    canvas.translate(-(c * InkTileMath.TILE).toFloat(), -(r * InkTileMath.TILE).toFloat())
                    if (clip) canvas.clipRect(left, top, right, bottom)
                    block(canvas)
                }
            }
        } finally {
            // 不让共用画布一直握着某一块：之后 recycle 才不会撞上它
            canvas.setBitmap(null)
        }
    }

    /** 擦掉矩形范围内已存在块的像素（不分配新块）。 */
    fun clearRect(left: Float, top: Float, right: Float, bottom: Float) {
        if (count == 0) return
        paint(left, top, right, bottom, create = false, clip = true) { c ->
            c.drawRect(left, top, right, bottom, clearPaint)
        }
    }

    /** 把所有已分配的块画到 [target]，位置即层坐标。 */
    fun draw(target: Canvas) {
        if (count == 0) return
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val bitmap = tiles[r * cols + c] ?: continue
                target.drawBitmap(
                    bitmap,
                    (c * InkTileMath.TILE).toFloat(), (r * InkTileMath.TILE).toFloat(),
                    null
                )
            }
        }
    }
}
