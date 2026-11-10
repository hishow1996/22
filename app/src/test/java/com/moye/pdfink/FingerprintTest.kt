package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FingerprintTest {

    /** 模拟某些 Provider 每次只吐几个字节的流。 */
    private class Chunked(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos >= data.size) -1 else (data[pos++].toInt() and 0xff)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, 7, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
        override fun skip(n: Long): Long {
            val k = minOf(n, (data.size - pos).toLong()).coerceAtLeast(0L)
            pos += k.toInt()
            return k
        }
    }

    @Test
    fun sameBytesGiveSameFingerprint() {
        val b = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(Fingerprint.fromBytes(b), Fingerprint.fromBytes(b))
    }

    @Test
    fun differentContentGivesDifferentFingerprint() {
        val a = ByteArray(200_000) { 1 }
        val b = ByteArray(200_000) { 1 }
        b[150_000] = 2
        assertNotEquals(Fingerprint.fromBytes(a), Fingerprint.fromBytes(b))
    }

    @Test
    fun providerChunkingDoesNotChangeResult() {
        val b = ByteArray(300_000) { (it * 7 % 233).toByte() }
        val normal = Fingerprint.from { ByteArrayInputStream(b) }
        val chunked = Fingerprint.from { Chunked(b) }
        assertEquals(normal, chunked)
    }

    @Test
    fun headMiddleTailAllContribute() {
        val base = ByteArray(200_000) { 3 }
        val head = base.copyOf().also { it[10] = 9 }
        val mid = base.copyOf().also { it[100_000] = 9 }
        val tail = base.copyOf().also { it[199_990] = 9 }
        val f0 = Fingerprint.fromBytes(base)
        assertNotEquals(f0, Fingerprint.fromBytes(head))
        assertNotEquals(f0, Fingerprint.fromBytes(mid))
        assertNotEquals(f0, Fingerprint.fromBytes(tail))
    }

    @Test
    fun emptyStreamIsStable() {
        assertEquals(
            Fingerprint.fromBytes(ByteArray(0)),
            Fingerprint.fromBytes(ByteArray(0))
        )
    }
}
