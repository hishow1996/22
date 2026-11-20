package com.moye.pdfink

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/**
 * v48：WebDAV 客户端协议测试。
 * 原版 fake server 用 JDK 内置 com.sun.net.httpserver——该包在 KGP 2.0
 * 给测试编译注入的 JDK API 快照里不可见（整包 Unresolved），改用纯
 * java.base 的 ServerSocket 手写最小 HTTP/1.1，任何 JDK 都能编译运行。
 * 测试断言与语义完全不变。
 */
class WebDavTest {

    private lateinit var server: FakeDavServer
    private lateinit var url: String

    private val okAuth = "Basic " + Base64.getEncoder().encodeToString("vv:secret".toByteArray())

    @Before
    fun start() {
        server = FakeDavServer()
        server.start()
        url = "http://127.0.0.1:${server.port}/dav/"
    }

    @After
    fun stop() { server.stop() }

    private fun client(pass: String = "secret") =
        WebDavClient(url, "vv", pass, allowHttp = true, timeoutMs = 3000)

    private val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 1, 2, 3)

    @Test
    fun uploadThenDownloadRoundTrips() {
        val c = client()
        assertNull(c.download())            // 还没有备份：连得上，只是没有
        assertNull(c.head())
        c.upload(zip)                        // 201
        assertArrayEquals(zip, c.download())
        c.upload(zip + 9)                    // 覆盖，204
        assertArrayEquals(zip + 9, c.download())
        assertEquals(zip.size + 1L, server.files["/dav/" + WebDavClient.REMOTE_NAME]!!.size.toLong())
        assertNotNull(c.head())
    }

    @Test
    fun sendsBasicAuthPreemptively() {
        client().upload(zip)
        assertEquals(okAuth, server.lastAuth)
    }

    @Test
    fun wrongPasswordIs401() {
        try { client("nope").upload(zip); fail("应抛 401") }
        catch (e: WebDavException) { assertEquals(401, e.code) }
        try { client("nope").download(); fail("应抛 401") }
        catch (e: WebDavException) { assertEquals(401, e.code) }
    }

    @Test
    fun redirectIsNotFollowed() {
        server.redirectPut = true
        try { client().upload(zip); fail("302 应当当作错误") }
        catch (e: WebDavException) { assertEquals(302, e.code) }
    }

    @Test
    fun nonBackupBodyIsRejected() {
        server.files["/dav/" + WebDavClient.REMOTE_NAME] = "<html>login</html>".toByteArray()
        try { client().download(); fail("不是 zip 应拒绝") }
        catch (e: WebDavException) { assertEquals(WebDavException.NOT_BACKUP, e.code) }
    }

    @Test
    fun unreachableServerIsNetworkError() {
        val dead = WebDavClient("http://127.0.0.1:1/dav/", "vv", "secret", allowHttp = true, timeoutMs = 1000)
        try { dead.download(); fail() }
        catch (e: WebDavException) { assertEquals(WebDavException.NETWORK, e.code) }
    }

    @Test
    fun urlRulesRequireHttpsAndNoEmbeddedCredentials() {
        assertEquals("https://dav.example.com/dav/", WebDavClient.normalizeBase(" https://dav.example.com/dav "))
        assertEquals("https://a.b/x/", WebDavClient.normalizeBase("https://a.b/x/"))
        assertNull(WebDavClient.normalizeBase("http://a.b/x/"))
        assertEquals("http://a.b/x/", WebDavClient.normalizeBase("http://a.b/x", allowHttp = true))
        assertNull(WebDavClient.normalizeBase("https://user:pw@a.b/x/"))
        assertNull(WebDavClient.normalizeBase("ftp://a.b/"))
        assertNull(WebDavClient.normalizeBase("   "))
        assertNull(WebDavClient.normalizeBase("https://"))
        try { WebDavClient("http://a.b/x/", "u", "p"); fail("默认不许 http") }
        catch (e: WebDavException) { assertEquals(WebDavException.BAD_URL, e.code) }
    }

    @Test
    fun backupMagicCheck() {
        assertTrue(WebDavClient.looksLikeBackup(zip))
        assertFalse(WebDavClient.looksLikeBackup("<html>".toByteArray()))
        assertFalse(WebDavClient.looksLikeBackup(byteArrayOf(1, 2)))
    }

    /** 最小 WebDAV 测试服务器：单连接串行处理，Connection: close。 */
    private class FakeDavServer {
        val files = HashMap<String, ByteArray>()
        var redirectPut = false
        var lastAuth: String? = null
        private val okAuth = "Basic " + Base64.getEncoder().encodeToString("vv:secret".toByteArray())

        private var socket: ServerSocket? = null
        val port: Int get() = socket!!.localPort

        fun start() {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            socket = s
            Thread {
                while (!s.isClosed) {
                    val conn = try { s.accept() } catch (_: Exception) { break }
                    try {
                        conn.soTimeout = 5000
                        serve(conn)
                    } catch (_: Exception) {
                    } finally {
                        try { conn.close() } catch (_: Exception) {}
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        fun stop() { try { socket?.close() } catch (_: Exception) {} }

        private fun serve(conn: Socket) {
            val inp = DataInputStream(conn.getInputStream())
            val out = conn.getOutputStream()

            // 头部按字节读到 \r\n\r\n（BufferedReader 会预读缓冲吞掉二进制 body）
            val head = ByteArrayOutputStream()
            var prev2 = 0; var prev1 = 0
            while (true) {
                val b = inp.read()
                if (b < 0) return
                head.write(b)
                if (prev2 == '\r'.code && prev1 == '\n'.code && b == '\r'.code) {
                    val n = inp.read()
                    if (n < 0) return
                    head.write(n)
                    break
                }
                prev2 = prev1; prev1 = b
            }
            val text = head.toString("UTF-8")
            val lines = text.trim().split("\r\n")
            val req = lines[0].split(" ")
            if (req.size < 2) { resp(out, 400); return }
            val method = req[0]
            val path = req[1]
            val headers = lines.drop(1).mapNotNull { l ->
                val i = l.indexOf(':')
                if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()

            lastAuth = headers["authorization"]
            if (lastAuth != okAuth) {
                resp(out, 401, mapOf("WWW-Authenticate" to "Basic realm=x")); return
            }

            when (method) {
                "PUT" -> {
                    if (redirectPut) {
                        resp(out, 302, mapOf("Location" to "http://127.0.0.1/elsewhere"))
                    } else {
                        val n = headers["content-length"]?.toInt() ?: 0
                        val body = ByteArray(n)
                        if (n > 0) inp.readFully(body)
                        val existed = files.containsKey(path)
                        files[path] = body
                        resp(out, if (existed) 204 else 201)
                    }
                }
                "GET" -> {
                    val b = files[path]
                    if (b == null) resp(out, 404) else resp(out, 200, body = b)
                }
                "HEAD" -> {
                    if (files.containsKey(path)) resp(out, 200) else resp(out, 404)
                }
                else -> resp(out, 405)
            }
        }

        private fun resp(out: OutputStream, code: Int, headers: Map<String, String> = emptyMap(), body: ByteArray? = null) {
            val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Content-Length: ${body?.size ?: 0}\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            body?.let { out.write(it) }
            out.flush()
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 302 -> "Found"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; else -> "Status"
        }
    }
}
