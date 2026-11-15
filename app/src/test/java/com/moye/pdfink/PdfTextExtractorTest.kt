package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfTextExtractorTest {

    private fun pdf(content: String, compressed: Boolean = false,
                    extra: String = "", encrypted: Boolean = false): ByteArray {
        val head = "%PDF-1.4\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R $extra/Contents 4 0 R >>\nendobj\n"
        val body = if (compressed) {
            val d = Deflater()
            d.setInput(content.toByteArray(Charsets.ISO_8859_1))
            d.finish()
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(256)
            while (!d.finished()) {
                val n = d.deflate(tmp)
                buf.write(tmp, 0, n)
            }
            d.end()
            "4 0 obj\n<< /Filter /FlateDecode >>\nstream\n" +
                String(buf.toByteArray(), Charsets.ISO_8859_1) + "\nendstream\nendobj\n"
        } else {
            "4 0 obj\n<< >>\nstream\n$content\nendstream\nendobj\n"
        }
        val enc = if (encrypted) " /Encrypt 9 0 R" else ""
        return (head + body + "trailer\n<< /Root 1 0 R$enc >>\n%%EOF\n")
            .toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun plainContentStreamYieldsText() {
        val pages = PdfTextExtractor.extract(
            pdf("BT (Hello World) Tj T* (Second) Tj ET"))
        assertEquals(1, pages.size)
        assertEquals("Hello World\nSecond", pages[0])
    }

    @Test fun flateDecodedStreamYieldsText() {
        val pages = PdfTextExtractor.extract(
            pdf("BT (Compressed Text) Tj ET", compressed = true))
        assertEquals(1, pages.size)
        assertTrue(pages[0].contains("Compressed Text"))
    }

    @Test fun tjArrayAndNegativeKernBecomeSpace() {
        val pages = PdfTextExtractor.extract(
            pdf("BT [(Hel) -250 (lo) -250 (World)] TJ ET"))
        assertEquals("Hel lo World", pages[0])
    }

    @Test fun encryptedAndEmptyAreRejected() {
        assertTrue(PdfTextExtractor.extract(ByteArray(0)).isEmpty())
        assertTrue(PdfTextExtractor.extract(
            pdf("BT (x) Tj ET", encrypted = true)).isEmpty())
        assertTrue(PdfTextExtractor.extract("not a pdf".toByteArray()).isEmpty())
    }

    @Test fun scannedPageHasNoText() {
        val pages = PdfTextExtractor.extract(pdf("q 612 0 0 792 0 0 cm /Im0 Do Q"))
        assertEquals(1, pages.size)
        assertTrue(pages[0].isEmpty())
    }

    @Test fun toUnicodeCmapMapsCjkCode() {
        val font = "5 0 obj\n<< /Type /Font /ToUnicode 6 0 R >>\nendobj\n" +
            "6 0 obj\n<< >>\nstream\n" +
            "begincmap\n1 beginbfchar\n<01> <4E2D>\nendbfchar\nendcmap\n" +
            "\nendstream\nendobj\n"
        val bytes = pdf("BT <01> Tj ET",
            extra = "/Resources << /Font << /F1 5 0 R >> >> ")
        // 把字体对象插在页对象之前：手工拼接一份带 CMap 的 PDF
        val s = String(bytes, Charsets.ISO_8859_1)
        val withFont = s.replace("trailer\n", font + "trailer\n")
        val pages = PdfTextExtractor.extract(withFont.toByteArray(Charsets.ISO_8859_1))
        assertEquals(1, pages.size)
        assertEquals("中", pages[0])
    }

    @Test fun overSizedInputIsRejected() {
        val huge = ByteArray(PdfTextExtractor.MAX_SOURCE_BYTES + 1)
        assertTrue(PdfTextExtractor.extract(huge).isEmpty())
    }
}
