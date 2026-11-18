package com.moye.pdfink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfAnnotWriterTest {

    /** 一页 200x100 的最小 PDF，经典交叉引用表。 */
    private fun minimalPdf(pageExtra: String = "", rotate: Int = 0): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offs = ArrayList<Int>()
        fun obj(n: Int, body: String) {
            offs.add(sb.length)
            sb.append("$n 0 obj\n$body\nendobj\n")
        }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        val rot = if (rotate != 0) " /Rotate $rotate" else ""
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100]$rot$pageExtra >>")
        val xref = sb.length
        sb.append("xref\n0 4\n0000000000 65535 f \n")
        for (o in offs) sb.append(String.format("%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun hl(vararg rect: Float) =
        AnnotInput(AnnotInput.HIGHLIGHT, 0xFFFFD60A.toInt(), 110, 0.0028f, rect)

    @Test
    fun kindsMatchShapes() {
        assertEquals(Shapes.FREE, AnnotInput.INK)
        assertEquals(Shapes.LINE, AnnotInput.LINE)
        assertEquals(Shapes.RECT, AnnotInput.RECT)
        assertEquals(Shapes.OVAL, AnnotInput.OVAL)
        assertEquals(Shapes.ARROW, AnnotInput.ARROW)
        assertEquals(Shapes.NOTE, AnnotInput.NOTE)
        assertEquals(Shapes.TEXT_HL, AnnotInput.HIGHLIGHT)
        assertEquals(Shapes.TEXT_UL, AnnotInput.UNDERLINE)
        assertEquals(Shapes.TEXT_STRIKE, AnnotInput.STRIKE)
    }

    @Test
    fun appendsIncrementalUpdateWithAnnotsAndXref() {
        val src = minimalPdf()
        val plan = PdfAnnotWriter.plan(src, mapOf(0 to listOf(hl(0.1f, 0.2f, 0.6f, 0.3f))), 0)
        assertNotNull(plan)
        assertEquals(1, plan!!.count)
        val upd = String(plan.update, Charsets.ISO_8859_1)
        assertTrue(upd.contains("/Subtype /Highlight"))
        assertTrue(upd.contains("/Annots ["))
        assertTrue(upd.contains("/Prev "))
        assertTrue(upd.trimEnd().endsWith("%%EOF"))

        // 交叉引用里每个偏移都必须正好落在「N G obj」上
        val full = String(src + plan.update, Charsets.ISO_8859_1)
        val xrefAt = full.lastIndexOf("\nxref\n") + 1   // 不能匹配到 startxref
        val lines = full.substring(xrefAt).lines()
        var checked = 0
        for (ln in lines) {
            val m = Regex("""^(\d{10}) (\d{5}) n $""").find(ln) ?: continue
            val off = m.groupValues[1].toInt()
            assertTrue("偏移 $off 不是对象起点", Regex("""^\d+ \d+ obj""").containsMatchIn(full.substring(off, off + 12)))
            checked++
        }
        assertTrue(checked >= 3)       // 批注 + 外观流 + 改写后的页对象
    }

    @Test
    fun prevPointsAtOriginalStartxref() {
        val src = minimalPdf()
        val text = String(src, Charsets.ISO_8859_1)
        val orig = Regex("""startxref\s+(\d+)""").find(text)!!.groupValues[1]
        val plan = PdfAnnotWriter.plan(src, mapOf(0 to listOf(hl(0f, 0f, 1f, 1f))), 0)!!
        assertTrue(String(plan.update, Charsets.ISO_8859_1).contains("/Prev $orig"))
    }

    @Test
    fun encryptedOrEmptyIsRejectedAndWritesNothing() {
        val enc = String(minimalPdf(), Charsets.ISO_8859_1)
            .replace("/Size 4", "/Size 4 /Encrypt 9 0 R").toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        assertEquals(-1, PdfAnnotWriter.write(enc, mapOf(0 to listOf(hl(0f, 0f, 1f, 1f))), 0, out))
        assertEquals(0, out.size())
        assertEquals(-1, PdfAnnotWriter.write(ByteArray(0), emptyMap(), 0, out))
    }

    @Test
    fun noAnnotationsMeansNoOutput() {
        val out = ByteArrayOutputStream()
        assertEquals(0, PdfAnnotWriter.write(minimalPdf(), mapOf(0 to emptyList()), 0, out))
        assertEquals(0, out.size())
    }

    @Test
    fun outputKeepsOriginalBytesUntouched() {
        val src = minimalPdf()
        val out = ByteArrayOutputStream()
        PdfAnnotWriter.write(src, mapOf(0 to listOf(hl(0.1f, 0.1f, 0.5f, 0.2f))), 0, out)
        val res = out.toByteArray()
        assertTrue(res.size > src.size)
        assertArrayEquals(src, res.copyOf(src.size))
    }

    @Test
    fun existingInlineAnnotsAreExtendedNotReplaced() {
        val src = minimalPdf(" /Annots [10 0 R]")
        val plan = PdfAnnotWriter.plan(src, mapOf(0 to listOf(hl(0f, 0f, 1f, 1f))), 0)!!
        val upd = String(plan.update, Charsets.ISO_8859_1)
        assertTrue(Regex("""/Annots \[10 0 R \d+ 0 R\]""").containsMatchIn(upd))
    }

    @Test
    fun xrefStreamSourceGetsXrefStreamUpdate() {
        // 只要最后一段交叉引用是流，更新也必须是流（并且不出现 trailer 关键字）
        val sb = StringBuilder("%PDF-1.5\n")
        fun obj(n: Int, body: String) { sb.append("$n 0 obj\n$body\nendobj\n") }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] >>")
        val xr = sb.length
        sb.append("4 0 obj\n<< /Type /XRef /Size 5 /W [1 2 1] /Root 1 0 R /Length 0 >>\nstream\n\nendstream\nendobj\n")
        sb.append("startxref\n$xr\n%%EOF\n")
        val plan = PdfAnnotWriter.plan(
            sb.toString().toByteArray(Charsets.ISO_8859_1),
            mapOf(0 to listOf(hl(0f, 0f, 1f, 1f))), 0
        )!!
        val upd = String(plan.update, Charsets.ISO_8859_1)
        assertTrue(upd.contains("/Type /XRef"))
        assertFalse(upd.contains("trailer"))
        assertTrue(upd.contains("/W [1 4 2]"))
    }

    @Test
    fun displayedCornersMapBackToPdfSpace() {
        // 不旋转：展示左上 = PDF 左上
        val g0 = PdfAnnotWriter.Geom(0f, 0f, 200f, 100f, 0)
        val p = FloatArray(2)
        g0.toPdf(0f, 0f, p); assertArrayEquals(floatArrayOf(0f, 100f), p, 0.001f)
        g0.toPdf(1f, 1f, p); assertArrayEquals(floatArrayOf(200f, 0f), p, 0.001f)
        // 页面顺时针转 90°：展示左上 = PDF 左下
        val g90 = PdfAnnotWriter.Geom(0f, 0f, 200f, 100f, 90)
        g90.toPdf(0f, 0f, p); assertArrayEquals(floatArrayOf(0f, 0f), p, 0.001f)
        g90.toPdf(1f, 0f, p); assertArrayEquals(floatArrayOf(0f, 100f), p, 0.001f)
        assertEquals(100f, g90.dispW, 0.001f)
        assertEquals(200f, g90.dispH, 0.001f)
        // 转 180°：展示左上 = PDF 右下
        val g180 = PdfAnnotWriter.Geom(0f, 0f, 200f, 100f, 180)
        g180.toPdf(0f, 0f, p); assertArrayEquals(floatArrayOf(200f, 0f), p, 0.001f)
    }

    @Test
    fun pdfRotateAndUserRotateAdd() {
        // 页自带 /Rotate 90，用户再转 90 = 总共 180：展示左上落在 PDF 右下
        val src = minimalPdf(rotate = 90)
        val plan = PdfAnnotWriter.plan(
            src, mapOf(0 to listOf(AnnotInput(AnnotInput.INK, 0xFF000000.toInt(), 255, 0.004f,
                floatArrayOf(0f, 0f, 0f, 0f)))), 90
        )!!
        assertTrue(String(plan.update, Charsets.ISO_8859_1).contains("/InkList [[200 0 200 0]]"))
    }

    @Test
    fun noteWithChineseTextIsHexEncoded() {
        val src = minimalPdf()
        val note = AnnotInput(AnnotInput.NOTE, 0xFF222226.toInt(), 255, 0.003f,
            floatArrayOf(0.5f, 0.5f), "测试")
        val upd = String(PdfAnnotWriter.plan(src, mapOf(0 to listOf(note)), 0)!!.update, Charsets.ISO_8859_1)
        assertTrue(upd.contains("/Contents <FEFF6D4B8BD5>"))
    }

    @Test
    fun textMarkupKeepsSelectedTextAsContents() {
        val a = AnnotInput(AnnotInput.HIGHLIGHT, 0xFFFFD60A.toInt(), 110, 0.0028f,
            floatArrayOf(0.1f, 0.2f, 0.6f, 0.3f), "key (point)")
        val upd = String(PdfAnnotWriter.plan(minimalPdf(), mapOf(0 to listOf(a)), 0)!!.update,
            Charsets.ISO_8859_1)
        assertTrue(upd.contains("/Contents (key \\(point\\))"))
        val plain = String(PdfAnnotWriter.plan(minimalPdf(), mapOf(0 to listOf(hl(0f, 0f, 1f, 1f))), 0)!!.update,
            Charsets.ISO_8859_1)
        assertFalse(plain.contains("/Contents"))
    }

    @Test
    fun matchBracketSkipsStringsAndNesting() {
        val s = "/Annots [1 0 R (a]b) 2 0 R] /X 1"
        val open = s.indexOf('[')
        assertEquals(s.indexOf("2 0 R]") + 5, PdfAnnotWriter.matchBracket(s, open))
        assertEquals(-1, PdfAnnotWriter.matchBracket("[1 2", 0))
    }

    @Test
    fun pageWithoutAnnotsGetsOneBeforeClosingBracket() {
        val nb = PdfAnnotWriter.pageWithAnnots("<< /Type /Page /MediaBox [0 0 1 1] >>", null, "7 0 R")!!
        assertTrue(nb.contains("/Annots [7 0 R]"))
        assertTrue(nb.trimEnd().endsWith(">>"))
        assertNull(PdfAnnotWriter.pageWithAnnots("not a dict", null, "7 0 R"))
    }
}
