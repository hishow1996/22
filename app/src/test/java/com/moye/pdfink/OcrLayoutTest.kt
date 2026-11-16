package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrLayoutTest {

    private fun line(vararg e: OcrElement) = OcrLine(e.toList())

    @Test fun chineseElementsAreSplitEvenlyAndLinesAreJoinedWithNewline() {
        val l = OcrLayoutBuilder.build(
            listOf(
                line(OcrElement("输入文件", 100f, 50f, 500f, 90f)),
                line(OcrElement("内容", 100f, 100f, 300f, 140f))
            ), 1000, 2000
        )
        assertEquals("输入文件\n内容", l.text)
        // 第 2 个字占元素宽度的 1/4..2/4：x 100+100..100+200，归一化后 0.2..0.3
        assertEquals(0.2f, l.boxes[4 * 1], 1e-4f)
        assertEquals(0.3f, l.boxes[4 * 1 + 2], 1e-4f)
        assertEquals(50f / 2000f, l.boxes[1], 1e-4f)
        // 换行符是合成的，不是真字
        assertFalse(l.real[4])
        assertTrue(l.real[0])
    }

    @Test fun latinWordsGetASpaceButChineseDoesNot() {
        val l = OcrLayoutBuilder.build(
            listOf(line(
                OcrElement("Hello", 0f, 0f, 50f, 10f),
                OcrElement("World", 60f, 0f, 110f, 10f),
                OcrElement("你好", 120f, 0f, 160f, 10f),
                OcrElement("世界", 170f, 0f, 210f, 10f)
            )), 400, 100
        )
        assertEquals("Hello World你好世界", l.text)
    }

    @Test fun searchRunsOnOcrTextAndMapsBackToBoxes() {
        val l = OcrLayoutBuilder.build(
            listOf(line(OcrElement("文学经典对于民族", 0f, 0f, 800f, 40f))), 800, 1000
        )
        val hit = DocumentSearch.find(listOf(l.text), "经典")[0]
        assertEquals(2, hit.start)
        assertEquals(4, hit.end)
        // 命中区间的字框确实是文本左侧 2/8..4/8
        assertEquals(0.25f, l.boxes[4 * hit.start], 1e-4f)
        assertEquals(0.5f, l.boxes[4 * (hit.end - 1) + 2], 1e-4f)
    }

    @Test fun emptyOrInvalidInputGivesEmptyLayout() {
        assertEquals("", OcrLayoutBuilder.build(emptyList(), 100, 100).text)
        assertEquals("", OcrLayoutBuilder.build(listOf(line(OcrElement("a", 0f, 0f, 1f, 1f))), 0, 100).text)
        assertEquals("", OcrLayoutBuilder.build(listOf(line(OcrElement("", 0f, 0f, 1f, 1f))), 100, 100).text)
    }

    @Test fun boxesAreClampedIntoThePage() {
        val l = OcrLayoutBuilder.build(listOf(line(OcrElement("字", -20f, -5f, 130f, 120f))), 100, 100)
        assertEquals(0f, l.boxes[0], 0f)
        assertEquals(0f, l.boxes[1], 0f)
        assertEquals(1f, l.boxes[2], 0f)
        assertEquals(1f, l.boxes[3], 0f)
    }

    @Test fun cacheRoundTripKeepsDoneBlankAndPendingPagesApart() {
        val dir = java.nio.file.Files.createTempDirectory("ocrcache").toFile().also { it.deleteOnExit() }
        val f = OcrCache.fileFor(dir, "fp-1")
        val done = OcrLayoutBuilder.build(listOf(line(OcrElement("全文搜索", 0f, 0f, 400f, 20f))), 400, 600)
        val pages = arrayOf<PageTextLayout?>(done, null, PageTextLayout.EMPTY)
        OcrCache.write(f, pages)
        val back = OcrCache.read(f, 3)!!
        assertEquals("全文搜索", back[0]!!.text)
        assertEquals(done.boxes.toList(), back[0]!!.boxes.toList())
        assertEquals(done.real.toList(), back[0]!!.real.toList())
        assertNull(back[1])                         // 还没识别
        assertNotNull(back[2])                      // 识别过但没有文字
        assertEquals("", back[2]!!.text)
        assertNull(OcrCache.read(f, 4))             // 页数不符
    }

    @Test fun corruptCacheIsIgnored() {
        val dir = java.nio.file.Files.createTempDirectory("ocrcache2").toFile().also { it.deleteOnExit() }
        val f = OcrCache.fileFor(dir, "x")
        f.parentFile!!.mkdirs()
        f.writeBytes(byteArrayOf(9, 9, 9))
        assertNull(OcrCache.read(f, 1))
    }
}
