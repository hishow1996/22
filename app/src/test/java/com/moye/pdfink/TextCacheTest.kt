package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextCacheTest {

    private fun tmpDir(): File =
        java.nio.file.Files.createTempDirectory("textcache").toFile().also { it.deleteOnExit() }

    @Test fun roundTripKeepsEveryPageIncludingBlankAndLongOnes() {
        val dir = tmpDir()
        val f = TextCache.fileFor(dir, "abc123")
        val pages = listOf("第一页\n输入文件内容", "", "x".repeat(70_000), "emoji 😀 与 ＡＢＣ")
        TextCache.write(f, pages)
        assertEquals(pages, TextCache.read(f, 4))
    }

    @Test fun pageCountMismatchOrMissingFileMeansNoCache() {
        val dir = tmpDir()
        val f = TextCache.fileFor(dir, "k")
        assertNull(TextCache.read(f, 2))
        TextCache.write(f, listOf("a", "b"))
        assertNull(TextCache.read(f, 3))
        assertNotNull(TextCache.read(f, 2))
    }

    @Test fun corruptFileIsTreatedAsMissing() {
        val dir = tmpDir()
        val f = TextCache.fileFor(dir, "bad")
        f.parentFile!!.mkdirs()
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(TextCache.read(f, 1))
    }

    @Test fun fileNameIsSafeAndVersioned() {
        val f = TextCache.fileFor(File("/x"), "../../etc/pass wd:1")
        assertFalse(f.name.contains("/"))
        assertFalse(f.name.contains(" "))
        assertTrue(f.name.endsWith(".v${TextCache.VERSION}.gz"))
        assertEquals("text-cache", f.parentFile!!.name)
    }

    @Test fun pruneKeepsTheMostRecentlyUsedFiles() {
        val dir = tmpDir()
        val names = (1..5).map { "d$it" }
        for ((i, n) in names.withIndex()) {
            val f = TextCache.fileFor(dir, n)
            TextCache.write(f, listOf("page $n"))
            f.setLastModified(1_000_000L + i * 1000L)
        }
        val cacheDir = TextCache.fileFor(dir, "d1").parentFile!!
        TextCache.prune(cacheDir, keepFiles = 3, keepBytes = Long.MAX_VALUE)
        val left = cacheDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(3, left.size)
        assertTrue(left.all { it.startsWith("d3") || it.startsWith("d4") || it.startsWith("d5") })
    }
}
