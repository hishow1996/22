package com.moye.pdfink

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.util.Base64

class WebDavTest {

    private lateinit var server: HttpServer
    private val files = HashMap<String, ByteArray>()
    private var redirectPut = false
    private var lastAuth: String? = null
    private lateinit var url: String

    private val okAuth = "Basic " + Base64.getEncoder().encodeToString("vv:secret".toByteArray())

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { ex ->
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            if (lastAuth != okAuth) {
                ex.responseHeaders.add("WWW-Authenticate", "Basic realm=x")
                ex.sendResponseHeaders(401, -1); ex.close(); return@createContext
            }
            handle(ex)
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/dav/"
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        when (ex.requestMethod) {
            "PUT" -> {
                if (redirectPut) {
                    ex.responseHeaders.add("Location", "http://127.0.0.1/elsewhere")
                    ex.sendResponseHeaders(302, -1)
                } else {
                    val existed = files.containsKey(path)
                    files[path] = ex.requestBody.readBytes()
                    ex.sendResponseHeaders(if (existed) 204 else 201, -1)
                }
            }
            "GET" -> {
                val b = files[path]
                if (b == null) ex.sendResponseHeaders(404, -1)
                else { ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b) }
            }
            "HEAD" -> {
                val b = files[path]
                if (b == null) ex.sendResponseHeaders(404, -1)
                else { ex.sendResponseHeaders(200, -1) }
            }
            else -> ex.sendResponseHeaders(405, -1)
        }
        ex.close()
    }

    @After
    fun stop() { server.stop(0) }

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
        assertEquals(zip.size + 1L, files["/dav/" + WebDavClient.REMOTE_NAME]!!.size.toLong())
        assertNotNull(c.head())
    }

    @Test
    fun sendsBasicAuthPreemptively() {
        client().upload(zip)
        assertEquals(okAuth, lastAuth)
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
        redirectPut = true
        try { client().upload(zip); fail("302 应当当作错误") }
        catch (e: WebDavException) { assertEquals(302, e.code) }
    }

    @Test
    fun nonBackupBodyIsRejected() {
        files["/dav/" + WebDavClient.REMOTE_NAME] = "<html>login</html>".toByteArray()
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
}
