package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupCodecTest {

    @Test
    fun exportImportRoundTrip() {
        val root = Files.createTempDirectory("moye-src").toFile()
        val dir = File(root, "ink_abc123").apply { mkdirs() }
        File(dir, "p0.json").writeText("{\"ops\":[]}")
        File(dir, "d3.json").writeText("{\"ops\":[1]}")
        val zip = ByteArrayOutputStream()
        assertEquals(2, BackupCodec.export(root, zip))

        val dest = Files.createTempDirectory("moye-dst").toFile()
        val n = BackupCodec.import(dest, ByteArrayInputStream(zip.toByteArray()))
        assertEquals(2, n)
        assertEquals("{\"ops\":[]}", File(dest, "ink_abc123/p0.json").readText())
        assertEquals("{\"ops\":[1]}", File(dest, "ink_abc123/d3.json").readText())
        root.deleteRecursively(); dest.deleteRecursively()
    }

    @Test
    fun zipSlipIsRejected() {
        val dest = Files.createTempDirectory("moye-safety").toFile()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("ink_abc123/../evil.json"))
            z.write("bad".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("moye-backup.json"))
            z.write("{}".toByteArray())
            z.closeEntry()
        }
        val n = BackupCodec.import(dest, ByteArrayInputStream(zip.toByteArray()))
        assertEquals(0, n)
        assertFalse(File(dest, "evil.json").exists())
        assertFalse(File(dest, "ink_abc123/../evil.json").exists())
        dest.deleteRecursively()
    }

    @Test
    fun foreignDirectoriesAreIgnored() {
        val root = Files.createTempDirectory("moye-mixed").toFile()
        File(root, "other_dir").apply { mkdirs() }
            .also { File(it, "p0.json").writeText("x") }
        val zip = ByteArrayOutputStream()
        assertEquals(0, BackupCodec.export(root, zip))
        root.deleteRecursively()
    }

    @Test
    fun metaEntryIsSkippedOnImport() {
        val dest = Files.createTempDirectory("moye-meta").toFile()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("moye-backup.json"))
            z.write("{\"v\":3}".toByteArray())
            z.closeEntry()
        }
        assertEquals(0, BackupCodec.import(dest, ByteArrayInputStream(zip.toByteArray())))
        assertTrue(dest.listFiles()?.isEmpty() ?: true)
        dest.deleteRecursively()
    }
}
