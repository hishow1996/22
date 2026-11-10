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
