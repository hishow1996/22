package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文字位置解析（页内选字的地基）与选区计算，纯 JVM。 */
class PdfTextLayoutTest {

    /** 把若干对象（从 1 号起）拼成最小 PDF；1 号必须是 Catalog。 */
    private fun pdf(vararg objs: String): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        objs.forEachIndexed { i, o -> sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        sb.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun stream(content: String, extra: String = ""): String =
        "<< /Length ${content.length} $extra >>\nstream\n$content\nendstream"

    private fun onePage(
        content: String, fonts: String, mediaBox: String = "[0 0 200 800]",
        pageExtra: String = "", extraObjs: Array<String> = emptyArray()
    ): ByteArray = pdf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox $mediaBox /Contents 4 0 R " +
            "/Resources << /Font << $fonts >> >> $pageExtra >>",
        stream(content),
        *extraObjs
    )

    private val abcFont = "/F1 5 0 R"
    private val abcFontObj =
        "<< /Type /Font /Subtype /Type1 /BaseFont /Foo /FirstChar 65 /LastChar 67 " +
            "/Widths [600 600 600] >>"

    @Test fun `glyph boxes follow font widths and text matrix`() {
        val doc = PdfLayoutDoc.open(
            onePage("BT /F1 10 Tf 100 700 Td (ABC) Tj ET", abcFont, extraObjs = arrayOf(abcFontObj))
        )
        assertNotNull(doc)
        val l = doc!!.layout(0)!!
        assertEquals("ABC", l.text)
        // 每字宽 6pt，页宽 200
        assertEquals(100f / 200f, l.boxes[0], 1e-4f)
        assertEquals(106f / 200f, l.boxes[2], 1e-4f)
        assertEquals(106f / 200f, l.boxes[4], 1e-4f)
        assertEquals(112f / 200f, l.boxes[8], 1e-4f)
        // 基线 700，上沿 +8、下沿 -2；页高 800，左上原点
        assertEquals(1f - 708f / 800f, l.boxes[1], 1e-4f)
        assertEquals(1f - 698f / 800f, l.boxes[3], 1e-4f)
    }

    @Test fun `TJ big gap becomes a space and new baseline becomes newline`() {
        val doc = PdfLayoutDoc.open(
            onePage(
                "BT /F1 10 Tf 100 700 Td [(A) -400 (B)] TJ 0 -20 Td (C) Tj ET",
                abcFont, extraObjs = arrayOf(abcFontObj)
            )
        )!!
        val l = doc.layout(0)!!
        assertEquals("A B\nC", l.text)
        assertEquals(booleanArrayOf(true, false, true, false, true).toList(), l.real.toList())
    }

    @Test fun `page rotate 90 rotates normalized boxes clockwise`() {
        val doc = PdfLayoutDoc.open(
            onePage(
                "BT /F1 10 Tf 0 790 Td (A) Tj ET", abcFont,
                pageExtra = "/Rotate 90", extraObjs = arrayOf(abcFontObj)
            )
        )!!
        val l = doc.layout(0)!!
        // 原页左上角附近的字，顺时针转 90° 后应在右上角附近
        assertTrue(l.boxes[0] > 0.9f)
        assertTrue(l.boxes[1] < 0.1f)
    }

    @Test fun `user rotation rotates layout too`() {
        val doc = PdfLayoutDoc.open(
            onePage("BT /F1 10 Tf 0 790 Td (A) Tj ET", abcFont, extraObjs = arrayOf(abcFontObj))
        )!!
        val l = doc.layout(0)!!
        val r = l.rotated(180)
        assertEquals(1f - l.boxes[2], r.boxes[0], 1e-4f)
        assertEquals(1f - l.boxes[3], r.boxes[1], 1e-4f)
        assertEquals(l.text, r.text)
    }

    @Test fun `type0 identity font uses ToUnicode and CID widths`() {
        val cmap = "beginbfrange\n<0001> <0003> <4E2D>\nendbfrange"
        val doc = PdfLayoutDoc.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 800] /Contents 4 0 R " +
                    "/Resources << /Font << /F1 5 0 R >> >> >>",
                stream("BT /F1 20 Tf 50 500 Td <000100020003> Tj ET"),
                "<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-H " +
                    "/DescendantFonts [6 0 R] /ToUnicode 7 0 R >>",
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /X /DW 1000 /W [1 [500 1000]] >>",
                stream(cmap)
            )
        )!!
        val l = doc.layout(0)!!
        assertEquals("中丮丯", l.text)
        assertEquals(50f / 200f, l.boxes[0], 1e-4f)
        assertEquals(60f / 200f, l.boxes[2], 1e-4f)   // 第一字 500 → 10pt
        assertEquals(80f / 200f, l.boxes[6], 1e-4f)   // 第二字 1000 → 20pt
        assertEquals(100f / 200f, l.boxes[10], 1e-4f) // 第三字走 DW
    }

    @Test fun `form xobject text is placed with its matrix`() {
        val doc = PdfLayoutDoc.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 800] /Contents 4 0 R " +
                    "/Resources << /XObject << /X1 6 0 R >> >> >>",
                stream("q 1 0 0 1 100 700 cm /X1 Do Q"),
                abcFontObj,
                "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources " +
                    "<< /Font << /F1 5 0 R >> >> /Length 28 >>\nstream\nBT /F1 10 Tf 0 0 Td (AB) Tj ET\nendstream"
            )
        )!!
        val l = doc.layout(0)!!
        assertEquals("AB", l.text)
        assertEquals(100f / 200f, l.boxes[0], 1e-4f)
    }

    @Test fun `page without text gives no layout`() {
        val doc = PdfLayoutDoc.open(
            onePage("0 0 100 100 re f", abcFont, extraObjs = arrayOf(abcFontObj))
        )!!
        assertNull(doc.layout(0))
    }

    @Test fun `ascii85 then nothing decodes`() {
        val out = PdfTextExtractor.decodeStream(
            "<< /Filter /ASCII85Decode >>", "87cURD_*#4DfTZ)+Wt]=~>".toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("Hello, World! ok", String(out, Charsets.ISO_8859_1))
    }

    // ---- 选区计算 ----

    private fun layoutOf(text: String): PageTextLayout {
        // 每字 0.05 宽、一行；'\n' 合成换行，换行后 y 下移
        val boxes = FloatArray(text.length * 4)
        val real = BooleanArray(text.length)
        var x = 0f
        var line = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                line++; x = 0f
                boxes[4 * i] = 0f; boxes[4 * i + 2] = 0f
                boxes[4 * i + 1] = line * 0.1f; boxes[4 * i + 3] = line * 0.1f + 0.05f
                continue
            }
            real[i] = true
            boxes[4 * i] = x; boxes[4 * i + 1] = line * 0.1f
            boxes[4 * i + 2] = x + 0.05f; boxes[4 * i + 3] = line * 0.1f + 0.05f
            x += 0.05f
        }
        return PageTextLayout(text, boxes, real)
    }

    @Test fun `hit test finds the char under the finger and ignores far points`() {
        val l = layoutOf("Hello World")
        // 画布 1000x1000：第 7 个字(索引 7 = 'o') 的框是 x 350..400, y 0..50
        assertEquals(7, TextSelectionMath.hitChar(l, 375f, 25f, 1000f, 1000f, 10f))
        assertEquals(-1, TextSelectionMath.hitChar(l, 375f, 500f, 1000f, 1000f, 10f))
    }

    @Test fun `word at picks the whole word and trims spaces`() {
        val l = layoutOf("Hello World")
        val w = TextSelectionMath.wordAt(l, 7)!!
        assertEquals("World", l.text.substring(w[0], w[1]))
        assertNull(TextSelectionMath.wordAt(l, 5)) // 空格不选
    }

    @Test fun `line rects split per line`() {
        val l = layoutOf("ab\ncd")
        val r = TextSelectionMath.lineRects(l, 0, l.length, 1000f, 1000f)
        assertEquals(8, r.size) // 两行 × 4
        assertEquals(0f, r[0], 1e-3f)
        assertEquals(100f, r[2], 1e-3f)
        assertEquals(100f, r[5], 1e-3f)
    }

    @Test fun `trim drops synthetic and blank edges`() {
        val l = layoutOf("a b\n")
        val t = TextSelectionMath.trim(l, 0, l.length)!!
        assertEquals(0, t[0])
        assertEquals(3, t[1])
    }

    @Test fun `nearest char while dragging prefers the same line`() {
        val l = layoutOf("abc\ndef")
        // 点在第二行下方一点、x 落在第 2 个字上
        val i = TextSelectionMath.nearestChar(l, 70f, 180f, 1000f, 1000f)
        assertEquals('e', l.text[i])
    }
}
