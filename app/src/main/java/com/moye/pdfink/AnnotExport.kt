package com.moye.pdfink

/**
 * v46：把墨页的批注（[Mark]）换成 [AnnotInput]，交给 [PdfAnnotWriter] 写成标准 PDF 注释。
 * 只取「p<页号>」这一份（右半页 d<页号> 是双页并排里的另一页，不属于这页 PDF）。
 */
object AnnotExport {

    /** 变宽笔迹（压感）写成固定线宽时取平均倍数，肉眼粗细与屏幕上一致。 */
    internal fun meanWidth(m: Mark): Float {
        val ws = m.ws ?: return m.width
        if (ws.isEmpty()) return m.width
        var s = 0f
        for (w in ws) s += w
        return m.width * (s / ws.size).coerceIn(0.2f, 4f)
    }

    fun toInput(m: Mark): AnnotInput? {
        if (m.pts.isEmpty()) return null
        val kind = when (m.shape) {
            Shapes.FREE -> AnnotInput.INK
            Shapes.LINE -> AnnotInput.LINE
            Shapes.RECT -> AnnotInput.RECT
            Shapes.OVAL -> AnnotInput.OVAL
            Shapes.ARROW -> AnnotInput.ARROW
            Shapes.NOTE -> AnnotInput.NOTE
            Shapes.TEXT_HL -> AnnotInput.HIGHLIGHT
            Shapes.TEXT_UL -> AnnotInput.UNDERLINE
            Shapes.TEXT_STRIKE -> AnnotInput.STRIKE
            else -> return null
        }
        if (kind == AnnotInput.NOTE && m.text.isNullOrBlank()) return null
        return AnnotInput(kind, m.color, m.alpha, meanWidth(m), m.pts, m.text)
    }

    /** 收集全本批注：只要可见图层，每页一份。没有批注的页不进 map。 */
    fun collect(
        pageCount: Int,
        layers: Set<Int>,
        load: (Int) -> List<Mark>
    ): Map<Int, List<AnnotInput>> {
        val out = LinkedHashMap<Int, List<AnnotInput>>()
        for (p in 0 until pageCount) {
            val list = load(p).asSequence()
                .filter { it.layer in layers }
                .mapNotNull { toInput(it) }
                .toList()
            if (list.isNotEmpty()) out[p] = list
        }
        return out
    }
}
