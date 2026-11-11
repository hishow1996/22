package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v28：类型映射、格式色、老记录回填、分页与旋转逆映射（纯 JVM）。 */
class FileTypeTest {

    @Test fun `extension extraction`() {
        assertEquals("pdf", FileType.ext("杨柳岸思维导图.pdf"))
        assertEquals("docx", FileType.ext("content://x/foo/期末.docx"))
        assertEquals("", FileType.ext("1234"))
        assertEquals("", FileType.ext(null))
    }

    @Test fun `common office color convention`() {
        assertEquals(FileKind.PDF, FileType.from("a.pdf", null))
        assertEquals(FileKind.WORD, FileType.from("a.docx", null))
        assertEquals(FileKind.EXCEL, FileType.from("a.xlsx", null))
        assertEquals(FileKind.PPT, FileType.from("a.pptx", null))
        assertEquals(FileKind.IMAGE, FileType.from("a.png", null))
        assertEquals(FileKind.TEXT, FileType.from("a.txt", null))
        assertEquals(FileKind.ZIP, FileType.from("a.zip", null))
        assertEquals(FileKind.OTHER, FileType.from("a.epub", null))
    }

    @Test fun `mime fallback when display name has no extension`() {
        assertEquals(FileKind.IMAGE, FileType.from("1234", "image/jpeg"))
        assertEquals(FileKind.PDF, FileType.from("scan", "application/pdf"))
        assertEquals(FileKind.WORD, FileType.from("1234",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

    @Test fun `csv and json are embedded text not spreadsheets`() {
        assertEquals(FileKind.TEXT, FileType.from("a.csv", null))
        assertEquals(FileKind.TEXT, FileType.from("a.json", null))
        assertTrue(FileKind.TEXT.embedded)
        assertFalse(FileKind.EXCEL.embedded)
    }

    @Test fun `pdf badge stays pixel identical`() {
        val k = FileKind.PDF
        assertEquals("PDF", k.label)
        assertEquals(0xFFD7463B.toInt(), k.color)
        assertEquals(16f, k.textSp)
        assertTrue(k.embedded)
        assertTrue(k.hasPages)
    }

    @Test fun `legacy record key backfills from filename`() {
        // 老记录没有 k 字段时 Recents.all() 用 FileType.from(name, null) 回填
        assertEquals("word", FileType.key(FileType.from("期末复习提纲.docx", null)))
        assertEquals("image", FileType.key(FileType.from("截图.png", null)))
        assertEquals("pdf", FileType.key(FileType.from("卷子.pdf", null)))
        assertEquals(FileKind.OTHER, FileKind.fromKey(""))
        assertEquals(FileKind.WORD, FileKind.fromKey("word"))
    }

    @Test fun `text pagination is stable`() {
        val p = TextLayout.paginate("a\nb\n", 40, 2)
        assertEquals(2, p.size)
        assertEquals(listOf("a", "b"), p[0])
        assertEquals(listOf(""), p[1])
        assertEquals(1, TextLayout.paginate("", 40, 40).size)
        val long = TextLayout.paginate("x".repeat(95), 40, 40)
        assertEquals(3, long[0].size)
    }

    @Test fun `rotation inverse keeps tile size after turning`() {
        val lw = 1080; val lh = 1528
        val left = 137; val top = 641; val tw = 400; val th = 320
        for (r in listOf(0, 90, 180, 270, -90, 450)) {
            val s = RasterRotation.inverse(lw, lh, left, top, tw, th, r)
            val n = RasterRotation.normalize(r)
            val outW = if (n == 90 || n == 270) s.height else s.width
            val outH = if (n == 90 || n == 270) s.width else s.height
            assertEquals("rotation $r width", tw, outW)
            assertEquals("rotation $r height", th, outH)
        }
    }
}
