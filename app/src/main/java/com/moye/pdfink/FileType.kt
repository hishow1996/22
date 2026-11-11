package com.moye.pdfink

/**
 * 浏览记录的文件类型：徽标规格、色值、是否内嵌渲染、副行口径的唯一来源。
 *
 * 参照微信 8.0.78 / WPS / Microsoft Office / Google Drive 的通行做法：
 * 彩色圆角方块 + 白色粗体缩写，不看后缀也能一眼分类型。
 * PDF 沿用图三实测的 #D7463B + 16sp「PDF」，逐像素不变。
 *
 * 纯 JVM，不依赖 Android，便于单测。
 */
enum class FileKind(
    val label: String,
    val tag: String,
    val color: Int,
    val textSp: Float,
    val embedded: Boolean,
    val hasPages: Boolean,
    val mime: String
) {
    PDF("PDF", "PDF", 0xFFD7463B.toInt(), 16f, true, true, "application/pdf"),
    WORD("W", "DOC", 0xFF2B579A.toInt(), 16f, false, false,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("X", "XLS", 0xFF217346.toInt(), 16f, false, false,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPT("P", "PPT", 0xFFD24726.toInt(), 16f, false, false,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    IMAGE("IMG", "IMG", 0xFF7B61D9.toInt(), 12f, true, false, "image/*"),
    TEXT("TXT", "TXT", 0xFF6B7280.toInt(), 12f, true, true, "text/plain"),
    ZIP("ZIP", "ZIP", 0xFFE0A22E.toInt(), 12f, false, false,
        "application/zip"),
    OTHER("FILE", "FILE", 0xFF8A8F98.toInt(), 10f, false, false, "*/*");

    companion object {
        fun fromKey(key: String?): FileKind = when (key?.lowercase()) {
            "pdf" -> PDF; "word", "doc" -> WORD; "excel", "xls" -> EXCEL
            "ppt" -> PPT; "image", "img" -> IMAGE; "text", "txt" -> TEXT
            "zip" -> ZIP; else -> OTHER
        }
    }
}

object FileType {
    /** 取扩展名，容错 content://.../1234 这种纯数字路径。 */
    fun ext(name: String?): String {
        val n = name?.trim()?.substringAfterLast('/') ?: return ""
        val i = n.lastIndexOf('.')
        return if (i in 0 until n.length - 1) n.substring(i + 1).lowercase() else ""
    }

    /**
     * 扩展名优先（DISPLAY_NAME 通常带后缀），MIME 兜底。
     * csv / json / xml 归 TEXT 而不是 EXCEL：它们是纯文本，能直接内嵌批注。
     */
    fun from(name: String?, mime: String?): FileKind {
        val e = ext(name)
        val m = mime?.lowercase().orEmpty()
        return when {
            e == "pdf" || m == "application/pdf" -> FileKind.PDF
            e in setOf("doc", "docx", "wps", "rtf", "odt") ||
                m.contains("wordprocessingml") || m.contains("msword") -> FileKind.WORD
            e in setOf("xls", "xlsx", "et", "ods") ||
                m.contains("spreadsheetml") || m.contains("ms-excel") -> FileKind.EXCEL
            e in setOf("ppt", "pptx", "dps", "odp") ||
                m.contains("presentationml") || m.contains("ms-powerpoint") -> FileKind.PPT
            e in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") ||
                m.startsWith("image/") -> FileKind.IMAGE
            e in setOf("txt", "md", "log", "csv", "json", "xml", "htm", "html") ||
                m.startsWith("text/") -> FileKind.TEXT
            e in setOf("zip", "rar", "7z", "tar", "gz", "bz2") ||
                m.contains("zip") || m.contains("compressed") ||
                m.contains("rar") -> FileKind.ZIP
            else -> FileKind.OTHER
        }
    }

    /** 记录里存的稳定键，避免存整个枚举名后再解析失败。 */
    fun key(k: FileKind): String = when (k) {
        FileKind.PDF -> "pdf"
        FileKind.WORD -> "word"
        FileKind.EXCEL -> "excel"
        FileKind.PPT -> "ppt"
        FileKind.IMAGE -> "image"
        FileKind.TEXT -> "text"
        FileKind.ZIP -> "zip"
        FileKind.OTHER -> "other"
    }
}
