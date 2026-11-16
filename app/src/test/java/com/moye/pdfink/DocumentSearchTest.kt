package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- v44：折叠匹配 ----------

    @Test fun lineBreaksInsideThePhraseDoNotBreakTheMatch() {
        // PDF 常把一句话拆成几段绘制，段与段之间被换行 / 空格隔开
        val text = "第一章\n输入\n文件\n内容 结束"
        val hits = DocumentSearch.find(listOf(text), "输入文件内容")
        assertEquals(1, hits.size)
        // 命中区间映射回原文：从「输」到「容」，中间的换行包含在内
        assertEquals(text.indexOf("输入"), hits[0].start)
        assertEquals(text.indexOf("内容") + 2, hits[0].end)
    }

    @Test fun queryMayContainSpacesThePageDoesNotHave() {
        assertEquals(1, DocumentSearch.find(listOf("输入文件内容"), "输入 文件").size)
        assertEquals(1, DocumentSearch.find(listOf("Hello World"), "hello  world").size)
        assertEquals(1, DocumentSearch.find(listOf("HelloWorld"), "hello world").size)
    }

    @Test fun fullWidthCompatAndAccentsAreFolded() {
        assertEquals(1, DocumentSearch.find(listOf("编号ＡＢＣ１２３"), "abc123").size)
        assertEquals(1, DocumentSearch.find(listOf("abc123"), "ＡＢＣ１２３").size)
        assertEquals(1, DocumentSearch.find(listOf("the ﬁnal word"), "final").size)
        assertEquals(1, DocumentSearch.find(listOf("Café au lait"), "cafe").size)
    }

    @Test fun zeroWidthAndSoftHyphenAreIgnored() {
        assertEquals(1, DocumentSearch.find(listOf("输\u200B入文\u00AD件"), "输入文件").size)
    }

    @Test fun hitRangeAndHighlightPointAtTheMatchedText() {
        // 前后各 48 字，超过片段半径（36），两端都应补省略号
        val text = "无关文字".repeat(12) + "输入\n文件\n内容" + "其他内容".repeat(12)
        val h = DocumentSearch.find(listOf(text), "输入文件内容")[0]
        // 片段里两个汉字之间的换行是断句留下的，不显示成空格
        assertEquals("输入文件内容", h.snippet.substring(h.snipStart, h.snipEnd))
        assertTrue(h.snippet.startsWith("…"))
        assertTrue(h.snippet.endsWith("…"))
    }

    @Test fun snippetDropsBreaksBetweenCjkButKeepsLatinSpaces() {
        val s = DocumentSearch.snippet("输入\n文件 abc \n def", 0, 2)
        assertTrue(s.contains("输入文件"))
        assertTrue(s.contains("abc def"))
    }

    @Test fun phraseMissButAllTermsOnOnePageFallsBackToFuzzy() {
        val pages = listOf(
            "本合同约定了付款节点。",
            "违约责任见下文，另有合同附件。"
        )
        val r = DocumentSearch.search(pages, "违约 合同")
        assertTrue(r.fuzzy)
        assertEquals(1, r.pageCount)
        assertTrue(r.hits.all { it.page == 1 })
        assertEquals(2, r.hits.size)
        // 完整短语能找到时不走兜底
        val exact = DocumentSearch.search(listOf("违约 合同"), "违约 合同")
        assertFalse(exact.fuzzy)
        assertEquals(1, exact.hits.size)
    }

    @Test fun singleMissingTermStaysEmpty() {
        val r = DocumentSearch.search(listOf("甲乙丙"), "丁")
        assertTrue(r.hits.isEmpty())
        assertFalse(r.fuzzy)
    }

    @Test fun cappedFlagMarksTruncatedResults() {
        val one = listOf("a ".repeat(300))
        val r = DocumentSearch.search(one, "a")
        assertEquals(200, r.hits.size)
        assertTrue(r.capped)
        val few = DocumentSearch.search(listOf("a a a"), "a")
        assertEquals(3, few.hits.size)
        assertFalse(few.capped)
    }

    @Test fun queryOfOnlyWhitespaceOrZeroWidthMatchesNothing() {
        assertTrue(DocumentSearch.find(pages, "\u200B").isEmpty())
        assertTrue(DocumentSearch.search(pages, " \n ").hits.isEmpty())
    }

}
