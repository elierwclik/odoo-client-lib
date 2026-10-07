/* Odoo Client Library Kotlin adaptation. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyrights and full license are in META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

class HttpTransportTest {
    private fun server(): TestHttpServer = TestHttpServer()
    private fun url(server: TestHttpServer, path: String): String = "http://127.0.0.1:${server.address.port}$path"

    @Test fun postsUtf8BodyHeadersAndReadsErrorResponses(): Unit = runBlocking {
        val server = server()
        var capturedBody = ""
        var capturedAuth: String? = null
        server.createContext("/test") {
            capturedBody = it.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            capturedAuth = it.requestHeaders.getFirst("Authorization")
            val bytes = "{\"name\":\"España\"}".toByteArray(Charsets.UTF_8)
            it.sendResponseHeaders(422, bytes.size.toLong())
            it.responseBody.use { body -> body.write(bytes) }
        }
        server.start()
        try {
            val response = UrlConnectionTransport().execute(HttpRequest(url(server, "/test"),
                headers = mapOf("Authorization" to "Bearer test-key", "Content-Type" to "application/json; charset=utf-8"),
                body = "{\"name\":\"España\"}"))
            assertEquals(422, response.statusCode)
            assertEquals(capturedBody, response.body)
            assertEquals("Bearer test-key", capturedAuth)
        } finally { server.stop(0) }
    }

    @Test fun retainsCookiesOnSameTransportAndKeepsInstancesIsolated(): Unit = runBlocking {
        val server = server()
        val cookies = mutableListOf<String?>()
        server.createContext("/") {
            cookies.add(it.requestHeaders.getFirst("Cookie"))
            it.responseHeaders.add("Set-Cookie", "session_id=abc; Path=/; HttpOnly")
            it.sendResponseHeaders(200, 2)
            it.responseBody.use { body -> body.write("{}".toByteArray()) }
        }
        server.start()
        try {
            val transport = UrlConnectionTransport()
            transport.execute(HttpRequest(url(server, "/"), method = "GET"))
            transport.execute(HttpRequest(url(server, "/"), method = "GET"))
            UrlConnectionTransport().execute(HttpRequest(url(server, "/"), method = "GET"))
            assertNull(cookies[0])
            assertTrue(cookies[1].orEmpty().contains("session_id=abc"))
            assertNull(cookies[2])
        } finally { server.stop(0) }
    }

    @Test fun doesNotFollowRedirectsWithCredentials(): Unit = runBlocking {
        val server = server()
        var followed = false
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", url(server, "/target"))
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        server.createContext("/target") {
            followed = true
            it.sendResponseHeaders(200, -1)
            it.close()
        }
        server.start()
        try {
            val response = UrlConnectionTransport().execute(HttpRequest(url(server, "/redirect"),
                headers = mapOf("Authorization" to "Bearer key"), body = "{}"))
            assertEquals(302, response.statusCode)
            assertFalse(followed)
        } finally { server.stop(0) }
    }

    @Test fun oversizedResponsesAreRejected(): Unit = runBlocking {
        val server = server()
        server.createContext("/") {
            val bytes = ByteArray(1024) { 65 }
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { body -> body.write(bytes) }
        }
        server.start()
        try {
            assertThrowsSuspend<ProtocolException> {
                UrlConnectionTransport(maxResponseBytes = 128).execute(HttpRequest(url(server, "/"), method = "GET"))
            }
        } finally { server.stop(0) }
    }

    @Test fun requestToStringOmitsSecretsAndPayload() {
        val request = HttpRequest("https://example.test", headers = mapOf("Authorization" to "secret"), body = "private data")
        assertFalse(request.toString().contains("secret"))
        assertFalse(request.toString().contains("private"))
    }
}

/** Small socket fixture using only Java APIs that also exist in Android's test classpath. */
private class TestHttpServer {
    private val socket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress
    private val handlers = mutableMapOf<String, (Exchange) -> Unit>()
    private var worker: Thread? = null
    @Volatile private var failure: Throwable? = null

    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }
    fun start() {
        worker = thread(isDaemon = true, name = "odoo-http-test") {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val input = client.getInputStream()
                        val path = line(input).split(' ')[1]
                        val headers = HeaderMap()
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            val parts = header.split(':', limit = 2)
                            headers.add(parts[0], parts[1].trim())
                        }
                        val size = headers.getFirst("Content-Length")?.toInt() ?: 0
                        val body = ByteArray(size)
                        var offset = 0
                        while (offset < size) {
                            val count = input.read(body, offset, size - offset)
                            check(count >= 0) { "Incomplete HTTP test request" }
                            offset += count
                        }
                        val exchange = Exchange(headers, body)
                        (handlers[path] ?: handlers["/"] ?: error("Missing handler for $path"))(exchange)
                        val response = exchange.responseBody.toByteArray()
                        val output = client.getOutputStream()
                        val head = buildString {
                            append("HTTP/1.1 ${exchange.status} Test\r\n")
                            exchange.responseHeaders.values.forEach { (key, value) -> append("$key: $value\r\n") }
                            append("Content-Length: ${response.size}\r\nConnection: close\r\n\r\n")
                        }
                        output.write(head.toByteArray(Charsets.US_ASCII))
                        output.write(response)
                        output.flush()
                    }
                }
            } catch (error: Throwable) {
                if (!(error is SocketException && socket.isClosed)) failure = error
            }
        }
    }
    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) {
        socket.close()
        worker?.join(5000)
        failure?.let { throw AssertionError("HTTP fixture failed", it) }
    }

    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            check(value >= 0) { "Unexpected EOF in HTTP headers" }
            if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(value)
        }
    }

    class HeaderMap {
        val values = mutableMapOf<String, String>()
        fun add(name: String, value: String) { values[name] = value }
        fun getFirst(name: String): String? = values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    class Exchange(val requestHeaders: HeaderMap, body: ByteArray) {
        val requestBody = ByteArrayInputStream(body)
        val responseBody = ByteArrayOutputStream()
        val responseHeaders = HeaderMap()
        var status: Int = 200
        fun sendResponseHeaders(code: Int, @Suppress("UNUSED_PARAMETER") length: Long) { status = code }
        fun close() = Unit
    }
}
