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

    /**
     * 批改章换成标准批注：✓ / ✗ 画成一条折线笔迹（✗ 在中心处折返，一笔画完两道），
     * ？写成带「?」文字的便签。答案遮罩只是学习辅助，不导出（返回 null）。
     */
    internal fun stampToInput(m: Mark): AnnotInput? {
        val r = CoverGeometry.rect(m.pts) ?: return null
        val w = r[2] - r[0]; val h = r[3] - r[1]
        val cx = (r[0] + r[2]) * 0.5f; val cy = (r[1] + r[3]) * 0.5f
        return when (m.shape) {
            Shapes.STAMP_OK -> AnnotInput(AnnotInput.INK, Stamps.color(m.shape), 255, 0.007f,
                floatArrayOf(
                    cx - 0.30f * w, cy + 0.02f * h,
                    cx - 0.08f * w, cy + 0.26f * h,
                    cx + 0.34f * w, cy - 0.26f * h
                ))
            Shapes.STAMP_BAD -> AnnotInput(AnnotInput.INK, Stamps.color(m.shape), 255, 0.007f,
                floatArrayOf(
                    cx - 0.26f * w, cy - 0.26f * h,
                    cx + 0.26f * w, cy + 0.26f * h,
                    cx, cy,
                    cx + 0.26f * w, cy - 0.26f * h,
                    cx, cy,
                    cx - 0.26f * w, cy + 0.26f * h
                ))
            Shapes.STAMP_Q -> AnnotInput(AnnotInput.NOTE, Stamps.color(m.shape), 255, 0.007f,
                floatArrayOf(cx, cy), "?")
            else -> null
        }
    }

    /**
     * @param outline v53：文字批注框的字形轮廓（Android 上才能算，所以由调用方给）。
     *  给 null / 算不出来就退回成一枚便签图标，文字放在批注里。
     */
    fun toInput(m: Mark, outline: ((Mark) -> TextBoxOutline?)? = null): AnnotInput? {
        if (m.pts.isEmpty()) return null
        if (Shapes.isStamp(m.shape)) return stampToInput(m)
        if (m.shape == Shapes.TEXTBOX) {
            if (m.text.isNullOrBlank() || m.pts.size < 4) return null
            val o = outline?.invoke(m)
            if (o != null && o.contours.isNotEmpty()) {
                return AnnotInput(AnnotInput.FREETEXT, m.color, m.alpha, 0f, o.box, m.text,
                    o.contours, TextBoxMath.hasBg(m.ws))
            }
            return AnnotInput(AnnotInput.NOTE, m.color, m.alpha, meanWidth(m),
                floatArrayOf(m.pts[0], m.pts[1]), m.text)
        }
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
        outline: ((page: Int, m: Mark) -> TextBoxOutline?)? = null,
        load: (Int) -> List<Mark>
    ): Map<Int, List<AnnotInput>> {
        val out = LinkedHashMap<Int, List<AnnotInput>>()
        for (p in 0 until pageCount) {
            val list = load(p).asSequence()
                .filter { it.layer in layers }
                .mapNotNull { m -> toInput(m, outline?.let { f -> { mk: Mark -> f(p, mk) } }) }
                .toList()
            if (list.isNotEmpty()) out[p] = list
        }
        return out
    }
}
