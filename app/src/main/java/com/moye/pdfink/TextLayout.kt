package com.moye.pdfink

/** 纯文本分页：不依赖 Android，便于 JVM 单测；渲染由 TextPageSource 负责。 */
object TextLayout {
    const val BASE_W = 1080f
    const val PAD = 56f
    const val LINE_H = 34f
    const val TEXT_SP = 24f
    const val LINES_PER_PAGE = 40
    const val CHAR_PER_LINE = 40
    val CANVAS_H: Float = PAD * 2 + LINES_PER_PAGE * LINE_H

    /** 先按换行切，再把过长行按固定字宽折行（英文尽量在空格处断）。 */
    fun paginate(
        text: String, charsPerLine: Int = CHAR_PER_LINE,
        linesPerPage: Int = LINES_PER_PAGE
    ): List<List<String>> {
        val flat = ArrayList<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            var rest = raw
            while (rest.length > charsPerLine) {
                var cut = charsPerLine
                val space = rest.lastIndexOf(' ', charsPerLine)
                if (space > charsPerLine / 2) cut = space + 1
                flat.add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
            flat.add(rest)
        }
        if (flat.isEmpty()) flat.add("")
        return flat.chunked(linesPerPage.coerceAtLeast(1))
    }
}
