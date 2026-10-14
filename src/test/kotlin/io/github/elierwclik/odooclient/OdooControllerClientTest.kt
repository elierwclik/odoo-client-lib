/* Odoo Client Library Kotlin adaptation. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyrights and full license are in META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OdooControllerClientTest {
    private class FakeTransport(private val responder: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return responder(request)
        }
    }

    private fun envelope(request: HttpRequest): Map<String, Any?> = stringMap(JsonCodec.decode(request.body!!))
    private fun result(request: HttpRequest, value: Any?): HttpResponse = HttpResponse(200, JsonCodec.encode(mapOf(
        "jsonrpc" to "2.0", "id" to envelope(request)["id"], "result" to value,
    )))
    private fun fault(request: HttpRequest, code: Int = 200, name: String = "odoo.exceptions.ValidationError"): HttpResponse =
        HttpResponse(200, JsonCodec.encode(mapOf(
            "jsonrpc" to "2.0", "id" to envelope(request)["id"],
            "error" to mapOf("code" to code, "data" to mapOf("name" to name)),
        )))

    private fun client(transport: HttpTransport): OdooControllerClient = OdooClient.controllers(
        "https://example.test/odoo/", "db", "user", "secret", transport,
    )

    @Test fun publicHttpPreservesBodyMethodHeadersAndErrorStatus(): Unit = runBlocking {
        val transport = FakeTransport { HttpResponse(422, "bad data") }
        val client = OdooClient.controllers("https://example.test/prefix", transport = transport)
        val response = client.http("/submit?mode=test", "POST", "name=Espa%C3%B1a", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        assertEquals(422, response.statusCode)
        assertEquals("bad data", response.body)
        val request = transport.requests.single()
        assertEquals("https://example.test/prefix/submit?mode=test", request.url)
        assertEquals("POST", request.method)
        assertEquals("name=Espa%C3%B1a", request.body)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertNull(client.userId)
    }

    @Test fun postJsonSendsTopLevelArrayAndRetainsAcceptedStatus(): Unit = runBlocking {
        val transport = FakeTransport { HttpResponse(202, """{"status":"accepted","received":1}""") }
        val rows = listOf(mapOf("id" to 123, "signure" to "base64"))
        val response = client(transport).postJson("/stock_entrego_app/signatures", rows)
        assertEquals(202, response.statusCode)
        assertEquals("accepted", stringMap(response.json())["status"])
        assertEquals(rows, JsonCodec.decode(transport.requests.single().body!!))
        assertEquals("application/json; charset=utf-8", transport.requests.single().headers["Content-Type"])
    }

    @Test fun publicJsonRpcUsesCustomPathAndNamedParamsWithoutLogin(): Unit = runBlocking {
        val transport = FakeTransport { result(it, mapOf("ok" to true)) }
        val controller = OdooClient.controllers("https://example.test", transport = transport)
        assertEquals(mapOf("ok" to true), controller.jsonRpc("/my/public/route", mapOf("ids" to listOf(1, 2))))
        val request = transport.requests.single()
        assertEquals("https://example.test/my/public/route", request.url)
        assertEquals(mapOf("ids" to listOf(1, 2)), envelope(request)["params"])
        assertEquals("call", envelope(request)["method"])
        assertEquals("2.0", envelope(request)["jsonrpc"])
    }

    @Test fun authenticatedRequestsLoginOnceWithWebCredentials(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/authenticate")) result(it, mapOf("uid" to 7)) else HttpResponse(200, "ok")
        }
        val controller = client(transport)
        controller.http("/private", authenticated = true)
        controller.http("/private", authenticated = true)
        assertEquals(3, transport.requests.size)
        val login = transport.requests.first()
        assertEquals("https://example.test/odoo/web/session/authenticate", login.url)
        assertEquals(mapOf("db" to "db", "login" to "user", "password" to "secret"), envelope(login)["params"])
        assertEquals(7, controller.userId)
    }

    @Test fun authenticatedJsonRpcUsesSessionBeforeController(): Unit = runBlocking {
        val transport = FakeTransport { result(it, if (it.url.endsWith("/authenticate")) mapOf("uid" to 7) else listOf(1, 2)) }
        assertEquals(listOf(1, 2), client(transport).jsonRpc("/private/json", authenticated = true))
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.last().url.endsWith("/private/json"))
    }

    @Test fun concurrentRequestsOnlyAuthenticateOnce(): Unit = runBlocking {
        var logins = 0
        val transport = FakeTransport {
            if (it.url.endsWith("/authenticate")) { logins++; result(it, mapOf("uid" to 7)) }
            else HttpResponse(200, "ok")
        }
        val controller = client(transport)
        (1..20).map { async { controller.http("/private", authenticated = true) } }.awaitAll()
        assertEquals(1, logins)
        assertEquals(21, transport.requests.size)
    }

    @Test fun failedForcedLoginClearsCachedSessionAndDoesNotCallController(): Unit = runBlocking {
        var logins = 0
        val transport = FakeTransport { result(it, if (++logins == 1) mapOf("uid" to 7) else mapOf("uid" to false)) }
        val controller = client(transport)
        assertEquals(7, controller.authenticate())
        assertThrowsSuspend<AuthenticationException> { controller.authenticate(force = true) }
        assertNull(controller.userId)
        assertEquals(2, transport.requests.size)
    }

    @Test fun accessDeniedOnLoginIsAnAuthenticationError(): Unit = runBlocking {
        val transport = FakeTransport { fault(it, name = "odoo.exceptions.AccessDenied") }
        assertThrowsSuspend<AuthenticationException> { client(transport).http("/private", authenticated = true) }
        assertEquals(1, transport.requests.size)
    }

    @Test fun expiredSessionIsNotReplayedAndNextCallLogsInAgain(): Unit = runBlocking {
        var calls = 0
        val transport = FakeTransport {
            when {
                it.url.endsWith("/authenticate") -> result(it, mapOf("uid" to 7))
                ++calls == 1 -> fault(it, 100, "odoo.http.SessionExpiredException")
                else -> result(it, true)
            }
        }
        val controller = client(transport)
        assertThrowsSuspend<AuthenticationException> { controller.jsonRpc("/write", authenticated = true) }
        assertEquals(1, calls)
        assertNull(controller.userId)
        assertEquals(true, controller.jsonRpc("/write", authenticated = true))
        assertEquals(4, transport.requests.size)
    }

    @Test fun rawHttp401ClearsCachedSessionAndPreservesStatus(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/authenticate")) result(it, mapOf("uid" to 7)) else HttpResponse(401, "denied")
        }
        val controller = client(transport)
        assertEquals(401, controller.http("/private", authenticated = true).statusCode)
        assertNull(controller.userId)
    }

    @Test fun preDispatchSessionFaultWithNullIdIsAuthenticationError(): Unit = runBlocking {
        val controller = client(FakeTransport {
            HttpResponse(200, """{"jsonrpc":"2.0","id":null,"error":{"code":100,"data":{"name":"odoo.http.SessionExpiredException"}}}""")
        })
        assertThrowsSuspend<AuthenticationException> { controller.jsonRpc("/private") }
        assertNull(controller.userId)
    }

    @Test fun unrelatedNullIdFaultIsNotAccepted(): Unit = runBlocking {
        assertThrowsSuspend<ProtocolException> { client(FakeTransport {
            HttpResponse(200, """{"jsonrpc":"2.0","id":null,"error":{"code":200}}""")
        }).jsonRpc("/private") }
    }

    @Test fun controllerFaultsRetainStructuredError(): Unit = runBlocking {
        val error = assertThrowsSuspend<RemoteException> { client(FakeTransport { fault(it) }).jsonRpc("/route") }
        assertEquals(200, error.code)
        assertEquals("odoo.exceptions.ValidationError", ((error.error as Map<*, *>)["data"] as Map<*, *>)["name"])
    }

    @Test fun invalidJsonRpcResponsesAndHttpErrorsAreRejected(): Unit = runBlocking {
        for (body in listOf("<html>login</html>", "{}", """{"jsonrpc":"2.0","id":999,"result":true}""")) {
            assertThrowsSuspend<ProtocolException> { client(FakeTransport { HttpResponse(200, body) }).jsonRpc("/route") }
        }
        assertThrowsSuspend<ProtocolException> { client(FakeTransport {
            HttpResponse(200, """{"jsonrpc":"2.0","id":${envelope(it)["id"]}}""")
        }).jsonRpc("/route") }
        val error = assertThrowsSuspend<HttpException> { client(FakeTransport { HttpResponse(503, "busy") }).jsonRpc("/route") }
        assertEquals(503, error.statusCode)
        assertThrowsSuspend<AuthenticationException> { client(FakeTransport { HttpResponse(401, "denied") }).jsonRpc("/route") }
    }

    @Test fun nullAndFalseRpcResultsAreValid(): Unit = runBlocking {
        assertNull(client(FakeTransport { result(it, null) }).jsonRpc("/route"))
        assertEquals(false, client(FakeTransport { result(it, false) }).jsonRpc("/route"))
    }

    @Test fun pathsAndMissingCredentialsFailBeforeNetwork(): Unit = runBlocking {
        val transport = FakeTransport { error("Unexpected network request") }
        val controller = client(transport)
        for (path in listOf("https://evil.test/path", "//evil.test/path", "/../escape", "/%2e%2e/escape", "/route#fragment")) {
            assertThrowsSuspend<IllegalArgumentException> { controller.http(path, authenticated = true) }
            assertThrowsSuspend<IllegalArgumentException> { controller.jsonRpc(path, authenticated = true) }
        }
        assertThrowsSuspend<IllegalArgumentException> {
            OdooClient.controllers("https://example.test", transport = transport).http("/private", authenticated = true)
        }
        assertThrowsSuspend<IllegalArgumentException> { controller.http("/route", body = "GET body") }
        assertThrows(IllegalArgumentException::class.java) { OdooClient.controllers("https://user:secret@example.test") }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun explicitJsonMethodsOverrideContentTypeCaseInsensitively(): Unit = runBlocking {
        val transport = FakeTransport { result(it, true) }
        client(transport).jsonRpc("/route", headers = mapOf("content-type" to "text/plain", "X-Test" to "value"))
        val headers = transport.requests.single().headers
        assertEquals(1, headers.keys.count { it.equals("Content-Type", ignoreCase = true) })
        assertEquals("application/json; charset=utf-8", headers["Content-Type"])
        assertEquals("value", headers["X-Test"])
    }

    @Test fun rpcClientExposesCachedControllerClientAndSeparatesWebLogin(): Unit = runBlocking {
        val transport = FakeTransport {
            result(it, if (it.url.endsWith("/authenticate")) mapOf("uid" to 7) else 7)
        }
        val rpc = OdooClient.rpc("https://example.test", "db", "user", "secret", transport = transport)
        assertEquals(7, rpc.checkLogin())
        assertNull(rpc.getControllers().userId)
        assertSame(rpc.getControllers(), rpc.getControllers())
        assertEquals(7, rpc.getControllers().authenticate())
        assertEquals(listOf("https://example.test/jsonrpc", "https://example.test/web/session/authenticate"), transport.requests.map { it.url })
        assertThrows(IllegalStateException::class.java) { OdooClient.json2("https://example.test", "key").getControllers() }
    }

    @Test fun realTransportCarriesSessionCookieToHttpAndJsonRpcControllers(): Unit = runBlocking {
        val server = TestHttpServer()
        val cookies = mutableListOf<String?>()
        server.createContext("/web/session/authenticate") {
            val request = HttpRequest("unused", body = it.requestBody.bufferedReader().readText())
            it.responseHeaders.add("Set-Cookie", "session_id=controller-session; Path=/; HttpOnly")
            it.responseBody.write(result(request, mapOf("uid" to 7)).body.toByteArray())
        }
        server.createContext("/private/http") {
            cookies.add(it.requestHeaders.getFirst("Cookie"))
            it.sendResponseHeaders(202, 0)
            it.responseBody.write("""{"status":"accepted"}""".toByteArray())
        }
        server.createContext("/private/json") {
            cookies.add(it.requestHeaders.getFirst("Cookie"))
            val request = HttpRequest("unused", body = it.requestBody.bufferedReader().readText())
            it.responseBody.write(result(request, true).body.toByteArray())
        }
        server.start()
        try {
            val controller = OdooClient.controllers("http://127.0.0.1:${server.address.port}", "db", "user", "secret")
            assertEquals(202, controller.postJson("/private/http", emptyList<Any?>(), authenticated = true).statusCode)
            assertEquals(true, controller.jsonRpc("/private/json", authenticated = true))
            assertEquals(2, cookies.size)
            assertTrue(cookies.all { it.orEmpty().contains("session_id=controller-session") })
        } finally { server.stop(0) }
    }
}
