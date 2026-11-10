package com.moye.pdfink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LowHeapInstrumentationTest {

    @Test
    fun largeSnapshotStillRestoresOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.filesDir, "snapshots_test")
        val k = SnapshotKeeper(root)
        val marks = List(400) { i ->
            Mark(0xFF222226.toInt(), 0.007f, 255, Shapes.FREE,
                floatArrayOf(i / 400f, 0.5f), null, Layers.ANSWER)
        }
        k.save("lowheap", mapOf("p0" to marks))
        val days = k.days("lowheap")
        assertEquals(1, days.size)
        assertEquals(400, k.restore("lowheap", days[0])["p0"]?.size)
        root.deleteRecursively()
    }

    @Test
    fun paletteIsStableAcrossRotations() {
        val pts = floatArrayOf(0.31f, 0.82f)
        var r = pts
        repeat(4) { r = InkPalette.rotatePts(r, 90) }
        assertTrue(Math.abs(r[0] - pts[0]) < 0.0001f)
        assertTrue(Math.abs(r[1] - pts[1]) < 0.0001f)
    }
}
