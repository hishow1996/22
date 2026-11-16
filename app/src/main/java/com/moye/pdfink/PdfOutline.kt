package com.moye.pdfink

/**
 * v45：PDF 自带的目录（书签树）里的一项。
 * page 是 0 基页号；目标解析不出来（外部链接、损坏的目标）时为 -1，界面只显示、不可点。
 * level 从 0 起，表示缩进层级。
 */
data class OutlineEntry(val title: String, val page: Int, val level: Int)

internal object PdfText {
    /** PDF 文本字符串：UTF-16BE / UTF-16LE / UTF-8（带 BOM），否则按 PDFDocEncoding（近似 Latin-1）。 */
    fun decode(b: ByteArray?): String {
        if (b == null || b.isEmpty()) return ""
        fun u(i: Int) = b[i].toInt() and 0xFF
        val s = when {
            b.size >= 2 && u(0) == 0xFE && u(1) == 0xFF -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            b.size >= 2 && u(0) == 0xFF && u(1) == 0xFE -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF ->
                String(b, 3, b.size - 3, Charsets.UTF_8)
            else -> String(b, Charsets.ISO_8859_1)
        }
        // 去掉 UTF-16 里的语言标记（ESC xx ESC）和控制字符
        return s.replace(Regex("\u001B[^\u001B]*\u001B"), "").filter { it >= ' ' || it == '\t' }
    }
}
