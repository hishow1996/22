package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SnapshotKeeperTest {

    private fun mark(x: Float) = Mark(
        0xFF222226.toInt(), 0.007f, 255, Shapes.FREE,
        floatArrayOf(x, x), null, Layers.ANSWER
    )

    @Test
    fun saveAndRestoreRoundTrip() {
        val root = Files.createTempDirectory("moye-snap").toFile()
        val k = SnapshotKeeper(root)
        val snap = mapOf<String, List<Mark>>(
            "p0" to listOf(mark(0.1f), mark(0.2f)),
            "d3" to listOf(mark(0.7f))
        )
        k.save("abc123", snap)
        val days = k.days("abc123")
        assertEquals(1, days.size)
        val back = k.restore("abc123", days[0])
        assertEquals(2, back["p0"]?.size)
        assertEquals(1, back["d3"]?.size)
        root.deleteRecursively()
    }

    @Test
    fun keepsAtMostThreeVersions() {
        val root = Files.createTempDirectory("moye-prune").toFile()
        val dir = java.io.File(root, "ink_xyz").apply { mkdirs() }
        for (d in listOf("20260101", "20260102", "20260103", "20260104", "20260105")) {
            java.io.File(dir, "$d.json").writeText("{}")
        }
        val k = SnapshotKeeper(root)
        k.save("xyz", mapOf("p0" to listOf(mark(0.5f))))
        assertTrue(k.days("xyz").size <= SnapshotKeeper.KEEP)
        root.deleteRecursively()
    }

    @Test
    fun invalidStampIsRejected() {
        val root = Files.createTempDirectory("moye-safety").toFile()
        val k = SnapshotKeeper(root)
        assertTrue(k.restore("abc", "../../evil").isEmpty())
        assertTrue(k.restore("abc", "").isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun emptySnapshotDoesNotCreateFiles() {
        val root = Files.createTempDirectory("moye-empty").toFile()
        SnapshotKeeper(root).save("abc", emptyMap())
        assertTrue(SnapshotKeeper(root).days("abc").isEmpty())
        root.deleteRecursively()
    }
}
