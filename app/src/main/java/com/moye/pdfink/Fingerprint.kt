package com.moye.pdfink

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 与 Provider 无关的内容指纹：长度 + 头 64K + 中 64K + 尾 64K。
 * 只顺序读流、不依赖 AssetFileDescriptor 或可 seek 的 channel，
 * 因此「同一份 PDF 从文件管理器 / 网盘 / 微信打开」结果一致。
 */
object Fingerprint {
    private const val BLK = 65536

    fun fromBytes(bytes: ByteArray): String = from { ByteArrayInputStream(bytes) }

    fun from(open: () -> InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(BLK)
        var headLen = 0
        val ring = ByteArray(BLK)
        var rp = 0
        var tailLen = 0
        var total = 0L
        val buf = ByteArray(BLK)
        try {
            open().use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    total += n
                    if (headLen < BLK) {
                        val k = minOf(n, BLK - headLen)
                        System.arraycopy(buf, 0, head, headLen, k)
                        headLen += k
                    }
                    for (k in 0 until n) {
                        ring[rp] = buf[k]
                        rp = (rp + 1) % BLK
                        if (tailLen < BLK) tailLen++
                    }
                }
            }
        } catch (e: Exception) {
            return fallback(total)
        }

        val mid = ByteArray(BLK)
        var midLen = 0
        val midStart = total / 2L
        try {
            open().use { s ->
                skipFully(s, midStart)
                while (midLen < BLK) {
                    val n = s.read(mid, midLen, BLK - midLen)
                    if (n <= 0) break
                    midLen += n
                }
            }
        } catch (e: Exception) {
            midLen = 0
        }

        val tail = ByteArray(tailLen)
        val start = (rp - tailLen + BLK) % BLK
        for (i in 0 until tailLen) tail[i] = ring[(start + i) % BLK]

        md.update(total.toString().toByteArray(Charsets.UTF_8))
        md.update(head, 0, headLen)
        md.update(mid, 0, midLen)
        md.update(tail, 0, tailLen)
        val hex = StringBuilder()
        for (b in md.digest()) hex.append("%02x".format(b.toInt() and 0xff))
        return hex.substring(0, 24)
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k > 0) left -= k
            else {
                if (s.read() < 0) return
                left--
            }
        }
    }

    private fun fallback(total: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("moye-fallback-$total".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder()
        for (b in md.digest()) hex.append("%02x".format(b.toInt() and 0xff))
        return hex.substring(0, 24)
    }
}
