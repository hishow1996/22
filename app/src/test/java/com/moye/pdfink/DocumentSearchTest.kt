package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSearchTest {

    private val pages = listOf(
        "第一章 绪论\n本章介绍 Alpha 与 beta 的概念。",
        "",
        "第二章 方法\nALPHA 的推导见附录，alpha 再出现一次。"
    )

    @Test fun caseInsensitiveAcrossPages() {
        val hits = DocumentSearch.find(pages, "alpha")
        assertEquals(3, hits.size)
        assertEquals(0, hits[0].page)
        assertEquals(2, hits[1].page)
        assertEquals(2, hits[2].page)
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(DocumentSearch.find(pages, "  ").isEmpty())
        assertTrue(DocumentSearch.find(emptyList(), "a").isEmpty())
    }

    @Test fun hitLimitIsRespected() {
        val one = listOf("a ".repeat(300))
        assertEquals(200, DocumentSearch.find(one, "a").size)
        assertEquals(3, DocumentSearch.find(one, "a", 3).size)
    }

    @Test fun snippetCollapsesWhitespaceAndMarksEdges() {
        val text = "前缀文字".repeat(8) + "命中词" + "后缀文字".repeat(8)
        val s = DocumentSearch.snippet(text, text.indexOf("命中词"), 3, 6)
        assertTrue(s.startsWith("…"))
        assertTrue(s.endsWith("…"))
        assertTrue(s.contains("命中词"))
        assertEquals("", DocumentSearch.collapse(" \n \t  "))
        assertEquals("a b", DocumentSearch.collapse("a \n b"))
    }

    @Test fun allTextKeepsPageNumbersAndSkipsEmptyPages() {
        val all = DocumentSearch.allText(pages)
        assertTrue(all.contains("第 1 页"))
        assertTrue(all.contains("第 3 页"))
        assertTrue(!all.contains("第 2 页"))
        assertEquals("", DocumentSearch.allText(listOf("", "  ")))
    }

    @Test fun hasTextDetectsOnlyRealContent() {
        assertTrue(DocumentSearch.hasText(pages))
        assertTrue(!DocumentSearch.hasText(listOf("", "\n")))
    }
}
