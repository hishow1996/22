package com.moye.pdfink

import org.json.JSONObject

/** 一个笔刷槽：长按存、单击切。 */
data class BrushPreset(
    val brushTool: Int = Tools.PEN,
    val color: Int = 0,
    val width: Int = BrushSizes.DEFAULT,
    val eraser: Int = BrushSizes.DEFAULT,
    val brushShape: Int = Shapes.LINE,
    val layer: Int = Layers.ANSWER
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", brushTool)
        .put("c", color)
        .put("w", width)
        .put("e", eraser)
        .put("s", brushShape)
        .put("l", layer)

    companion object {
        fun fromJson(o: JSONObject): BrushPreset = BrushPreset(
            o.optInt("t", Tools.PEN),
            o.optInt("c", 0),
            o.optInt("w", BrushSizes.DEFAULT),
            o.optInt("e", BrushSizes.DEFAULT),
            o.optInt("s", Shapes.LINE),
            o.optInt("l", Layers.ANSWER)
        )
    }
}

/** 每份卷子的连续性状态，按内容指纹归档。 */
data class DocSession(
    val page: Int = 0,
    val zoom: Float = 1f,
    val brushTool: Int = Tools.PEN,
    val layer: Int = Layers.ANSWER,
    val tint: Int = PageFilter.WHITE,
    val brightness: Float = 0f,
    val rotation: Int = 0,
    val showCorrection: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("p", page)
        .put("z", zoom.toDouble())
        .put("t", brushTool)
        .put("l", layer)
        .put("ti", tint)
        .put("b", brightness.toDouble())
        .put("r", rotation)
        .put("sc", showCorrection)

    companion object {
        fun fromJson(o: JSONObject): DocSession = DocSession(
            o.optInt("p", 0),
            o.optDouble("z", 1.0).toFloat(),
            o.optInt("t", Tools.PEN),
            o.optInt("l", Layers.ANSWER),
            o.optInt("ti", PageFilter.WHITE),
            o.optDouble("b", 0.0).toFloat(),
            o.optInt("r", 0),
            o.optBoolean("sc", true)
        )
    }
}
