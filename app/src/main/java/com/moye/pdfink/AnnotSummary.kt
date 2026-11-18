package com.moye.pdfink

/**
 * v46：批注汇总。把全本批注整理成「按页排序的清单」，供跳转面板展示、点一下跳页。
 * 纯逻辑，不依赖 Android，方便单测。
 *
 * 规则：
 *  - 高亮 / 下划线 / 删除线 / 便签：一条批注一行，附原文或便签内容的摘录；
 *  - 手写笔迹和形状（直线、箭头、矩形、椭圆）太碎，每页合并成一行「手写 N 笔 · 形状 M 个」；
 *  - 只算可见图层，和屏幕上看到的、导出的一致。
 */
object AnnotSummary {

    const val HIGHLIGHT = 0
    const val UNDERLINE = 1
    const val STRIKE = 2
    const val NOTE = 3
    const val INK = 4

    /** strokes / shapes 只在 kind == INK 时有意义；snippet 只在文字类和便签有。 */
    data class Entry(
        val page: Int,
        val kind: Int,
        val snippet: String = "",
        val strokes: Int = 0,
        val shapes: Int = 0
    )

    /** 压缩空白并截断；多余部分用省略号。 */
    fun snippet(raw: String?, max: Int = 60): String {
        if (raw.isNullOrBlank()) return ""
        val t = raw.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.substring(0, max).trimEnd() + "…"
    }

    fun build(
        pageCount: Int,
        layers: Set<Int>,
        maxEntries: Int = 500,
        load: (Int) -> List<Mark>
    ): List<Entry> {
        val out = ArrayList<Entry>()
        for (p in 0 until pageCount) {
            if (out.size >= maxEntries) break
            var strokes = 0
            var shapes = 0
            for (m in load(p)) {
                if (m.layer !in layers) continue
                when (m.shape) {
                    Shapes.TEXT_HL -> out.add(Entry(p, HIGHLIGHT, snippet(m.text)))
                    Shapes.TEXT_UL -> out.add(Entry(p, UNDERLINE, snippet(m.text)))
                    Shapes.TEXT_STRIKE -> out.add(Entry(p, STRIKE, snippet(m.text)))
                    Shapes.NOTE -> if (!m.text.isNullOrBlank()) out.add(Entry(p, NOTE, snippet(m.text)))
                    Shapes.FREE -> if (m.pts.isNotEmpty()) strokes++
                    Shapes.LINE, Shapes.RECT, Shapes.OVAL, Shapes.ARROW -> shapes++
                }
            }
            if (strokes + shapes > 0) out.add(Entry(p, INK, "", strokes, shapes))
        }
        return out
    }

    /** 各类批注总数，用于面板标题。 */
    fun total(entries: List<Entry>): Int = entries.size
}
