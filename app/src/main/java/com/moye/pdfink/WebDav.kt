package com.moye.pdfink

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * v46：WebDAV 备份同步的最小客户端，只用 JDK 自带的 HttpURLConnection，不引入新依赖。
 *
 * 只用 PUT / GET / HEAD：Android 的 HttpURLConnection 不允许 PROPFIND / MKCOL 这类扩展方法，
 * 所以**目录必须事先存在**（坚果云、Nextcloud、群晖 / 威联通 NAS 等在网页里先建好文件夹即可）。
 * 纯 JVM 实现，本地用内置 HttpServer 起一个假 WebDAV 服务就能单测。
 */
class WebDavException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val NETWORK = -1       // 连不上 / 超时 / 证书问题
        const val BAD_URL = -2       // 地址不合法，或不是 https
        const val TOO_BIG = -3       // 云端文件超过上限
        const val NOT_BACKUP = -4    // 下载到的不是墨页备份（多半是登录页）
    }
}

class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val pass: String,
    private val allowHttp: Boolean = false,
    private val timeoutMs: Int = 20_000
) {
    companion object {
        const val REMOTE_NAME = "moye-backup.moye"
        const val MAX_DOWNLOAD = 64 * 1024 * 1024

        /** 去空白、补末尾斜杠；只认 https（allowHttp 供本地测试），不认地址里自带账号密码。 */
        fun normalizeBase(raw: String, allowHttp: Boolean = false): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val lower = s.lowercase()
            val okScheme = lower.startsWith("https://") || (allowHttp && lower.startsWith("http://"))
            if (!okScheme) return null
            val u = try { URL(s) } catch (e: Exception) { return null }
            if (u.host.isNullOrEmpty() || u.userInfo != null) return null
            return if (s.endsWith("/")) s else "$s/"
        }

        /** 备份是 zip：必须以 PK 开头。服务器把登录页当 200 返回时靠这个拦下。 */
        fun looksLikeBackup(b: ByteArray): Boolean =
            b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    }

    private val base: String = normalizeBase(baseUrl, allowHttp)
        ?: throw WebDavException(WebDavException.BAD_URL, "bad url")

    private val auth: String? =
        if (user.isEmpty() && pass.isEmpty()) null
        else "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    /** 云端文件的概况；不存在返回 null（说明连得上、认证也过了，只是还没有备份）。 */
    class Info(val size: Long, val modified: Long)

    private fun open(method: String): HttpURLConnection {
        val c = try {
            URL(base + REMOTE_NAME).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw WebDavException(WebDavException.BAD_URL, "bad url")
        }
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false          // PUT 的重定向不能安全重放，直接当错误报
        c.useCaches = false
        auth?.let { c.setRequestProperty("Authorization", it) }
        return c
    }

    private fun fail(code: Int): Nothing = throw WebDavException(code, "http $code")

    private inline fun <T> net(block: () -> T): T = try {
        block()
    } catch (e: WebDavException) {
        throw e
    } catch (e: Exception) {
        throw WebDavException(WebDavException.NETWORK, e.message ?: "network")
    }

    fun head(): Info? = net {
        val c = open("HEAD")
        try {
            when (val code = c.responseCode) {
                200 -> Info(c.contentLengthLong, c.lastModified)
                404 -> null
                else -> fail(code)
            }
        } finally { c.disconnect() }
    }

    fun upload(data: ByteArray) = net {
        val c = open("PUT")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(data.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            if (code != 200 && code != 201 && code != 204) fail(code)
        } finally { c.disconnect() }
    }

    /** 云端没有备份返回 null。 */
    fun download(): ByteArray? = net {
        val c = open("GET")
        try {
            val code = c.responseCode
            if (code == 404) return@net null
            if (code != 200) fail(code)
            if (c.contentLengthLong > MAX_DOWNLOAD) {
                throw WebDavException(WebDavException.TOO_BIG, "too big")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD) {
                        throw WebDavException(WebDavException.TOO_BIG, "too big")
                    }
                }
            }
            val bytes = out.toByteArray()
            if (!looksLikeBackup(bytes)) {
                throw WebDavException(WebDavException.NOT_BACKUP, "not a backup")
            }
            bytes
        } finally { c.disconnect() }
    }
}
