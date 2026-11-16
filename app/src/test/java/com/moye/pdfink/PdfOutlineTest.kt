package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOutlineTest {

    private fun pdf(vararg objs: Pair<Int, String>): ByteArray {
        val sb = StringBuilder("%PDF-1.5\n")
        for ((n, body) in objs) sb.append("$n 0 obj\n$body\nendobj\n")
        sb.append("trailer\n<< /Root 1 0 R /Size ${objs.size + 1} >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private val pages = arrayOf(
        2 to "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>",
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] >>",
        4 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] >>",
        5 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] >>"
    )

    @Test fun nestedOutlineWithDirectGoToAndNamedDestinations() {
        val bytes = pdf(
            1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R /Names << /Dests 20 0 R >> >>",
            *pages,
            10 to "<< /Type /Outlines /First 11 0 R /Last 13 0 R /Count 4 >>",
            11 to "<< /Title (Chapter 1) /Parent 10 0 R /Dest [3 0 R /XYZ 0 800 null] " +
                "/First 14 0 R /Last 14 0 R /Next 12 0 R >>",
            14 to "<< /Title <FEFF7B2C4E008282> /Parent 11 0 R /Dest [4 0 R /Fit] >>",
            12 to "<< /Title (Chapter 2) /Parent 10 0 R /Dest (named2) /Next 13 0 R >>",
            13 to "<< /Title (Chapter 3) /Parent 10 0 R /A << /S /GoTo /D [5 0 R /Fit] >> >>",
            20 to "<< /Names [(named2) [4 0 R /XYZ 0 0 0]] >>"
        )
        val items = PdfLayoutDoc.open(bytes)!!.outline()
        assertEquals(
            listOf(
                OutlineEntry("Chapter 1", 0, 0),
                OutlineEntry("第一节", 1, 1),
                OutlineEntry("Chapter 2", 1, 0),
                OutlineEntry("Chapter 3", 2, 0)
            ),
            items
        )
    }

    @Test fun legacyDestsDictionaryAndUnresolvedTargets() {
        val bytes = pdf(
            1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R /Dests 21 0 R >>",
            *pages,
            10 to "<< /Type /Outlines /First 11 0 R >>",
            11 to "<< /Title (Old style) /Dest /intro /Next 12 0 R >>",
            12 to "<< /Title (Broken) /Dest /nowhere >>",
            21 to "<< /intro [5 0 R /Fit] >>"
        )
        val items = PdfLayoutDoc.open(bytes)!!.outline()
        assertEquals(2, items.size)
        assertEquals(2, items[0].page)
        assertEquals(-1, items[1].page)      // 解析不出来：保留标题、页号 -1
    }

    @Test fun cyclicNextPointersTerminate() {
        val bytes = pdf(
            1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R >>",
            *pages,
            10 to "<< /Type /Outlines /First 11 0 R >>",
            11 to "<< /Title (A) /Dest [3 0 R /Fit] /Next 12 0 R >>",
            12 to "<< /Title (B) /Dest [4 0 R /Fit] /Next 11 0 R >>"
        )
        val items = PdfLayoutDoc.open(bytes)!!.outline()
        assertEquals(listOf("A", "B"), items.map { it.title })
    }

    @Test fun documentWithoutOutlineGivesEmptyList() {
        val bytes = pdf(1 to "<< /Type /Catalog /Pages 2 0 R >>", *pages)
        assertTrue(PdfLayoutDoc.open(bytes)!!.outline().isEmpty())
    }

    @Test fun textStringDecoding() {
        assertEquals("abc", PdfText.decode("abc".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("第", PdfText.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x7B, 0x2C)))
        assertEquals("第", PdfText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x2C, 0x7B)))
        assertEquals("第", PdfText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
            0xE7.toByte(), 0xAC.toByte(), 0xAC.toByte())))
        assertEquals("", PdfText.decode(null))
    }
}
