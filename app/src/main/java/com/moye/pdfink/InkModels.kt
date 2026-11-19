package com.moye.pdfink

import org.json.JSONArray
import org.json.JSONObject

object Shapes {
    const val FREE = 0
    const val LINE = 1
    const val RECT = 2
    const val OVAL = 3
    const val ARROW = 4
    const val NOTE = 5

    /**
     * v46 文字标注：吸附在 PDF 文字上的高亮 / 下划线 / 删除线。
     * pts 不是「起点终点」，而是每行一个矩形，flat 为 x0,y0,x1,y1（归一化）。
     */
    const val TEXT_HL = 6
    const val TEXT_UL = 7
    const val TEXT_STRIKE = 8

    fun isTextMarkup(shape: Int): Boolean =
        shape == TEXT_HL || shape == TEXT_UL || shape == TEXT_STRIKE

    /** v46 答案 / 解析遮罩：pts 是两个角点（和矩形同口径）；「揭开」只在内存里，不落盘。 */
    const val COVER = 9

    /** v46 批改章：pts 是章的外接矩形（两个角点）；形状本身由矩形内居中绘制。 */
    const val STAMP_OK = 10
    const val STAMP_BAD = 11
    const val STAMP_Q = 12

    fun isStamp(shape: Int): Boolean = shape == STAMP_OK || shape == STAMP_BAD || shape == STAMP_Q
}

/** v46 批改章：固定颜色、固定大小（页面短边的 5%）。 */
object Stamps {
    const val SIZE_FRAC = 0.05f
    fun color(shape: Int): Int = when (shape) {
        Shapes.STAMP_OK -> 0xFF2E9E4F.toInt()    // 对：绿
        Shapes.STAMP_BAD -> 0xFFE53935.toInt()   // 错：红
        else -> 0xFFF2A03D.toInt()               // 存疑：橙
    }
}

/**
 * 一条笔迹 / 一个图形 / 一张便签。
 * 坐标归一化到页面宽高，粗细归一化到短边，换屏、旋转、缩放都能还原。
 * layer: 0 = 第一遍作答，1 = 订正层。
 */
data class Mark(
    val color: Int,
    val width: Float,
    val alpha: Int = 255,
    val shape: Int = Shapes.FREE,
    val pts: FloatArray,
    val ws: FloatArray? = null,
    val layer: Int = 0,
    val text: String? = null
) {
    /** v46：遮罩是否已被揭开。只活在内存里（不进 JSON / copy / equals），重新打开又盖上。 */
    var revealed: Boolean = false

    fun toJson(): JSONObject {
        val a = JSONArray()
        for (v in pts) a.put(v.toDouble())
        val o = JSONObject().apply {
            put("c", color)
            put("w", width.toDouble())
            put("a", alpha)
            put("s", shape)
            put("l", layer)
            put("p", a)
        }
        ws?.let {
            val b = JSONArray()
            for (v in it) b.put(v.toDouble())
            o.put("k", b)
        }
        text?.let { o.put("tx", it) }
        return o
    }

    fun copyWith(
        pts: FloatArray = this.pts,
        ws: FloatArray? = this.ws,
        width: Float = this.width,
        text: String? = this.text
    ): Mark = Mark(color, width, alpha, shape, pts, ws, layer, text)

    companion object {
        fun fromJson(o: JSONObject): Mark {
            val a = o.optJSONArray("p") ?: JSONArray()
            val pts = FloatArray(a.length()) { i -> a.optDouble(i, 0.0).toFloat() }
            val b = o.optJSONArray("k")
            val ws = if (b != null && b.length() == pts.size / 2) {
                FloatArray(b.length()) { i -> b.optDouble(i, 1.0).toFloat() }
            } else null
            return Mark(
                o.optInt("c", 0xFF222226.toInt()),
                o.optDouble("w", 0.007).toFloat(),
                o.optInt("a", 255),
                o.optInt("s", Shapes.FREE),
                pts, ws,
                o.optInt("l", 0),
                o.optString("tx", "").takeIf { it.isNotEmpty() }
            )
        }
    }
}
