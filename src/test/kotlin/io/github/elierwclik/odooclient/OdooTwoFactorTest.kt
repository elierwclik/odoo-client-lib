/* Kotlin adaptation of Odoo's odoo-client-lib. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyright holders and full license: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OdooTwoFactorTest {
    private class FakeTransport(val responder: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return responder(request)
        }
    }

    private fun result(request: HttpRequest, value: Any?): HttpResponse {
        val envelope = stringMap(JsonCodec.decode(request.body!!))
        return HttpResponse(200, JsonCodec.encode(mapOf("jsonrpc" to "2.0", "id" to envelope["id"], "result" to value)))
    }
    private fun form(csrf: String = "csrf-first"): HttpResponse = HttpResponse(200,
        """<form><input value='$csrf' type="hidden" name='csrf_token'/><input name="totp_token"/></form>""")
    private fun routes(transport: HttpTransport): OdooControllerClient =
        OdooClient.controllers("https://example.test/prefix", "db", "user", "secret", transport)

    private fun responder(request: HttpRequest): HttpResponse = when {
        request.url.endsWith("/authenticate") -> result(request, mapOf("uid" to null))
        request.url.endsWith("/totp") && request.method == "GET" -> form()
        request.url.endsWith("/totp") -> HttpResponse(303, "")
        request.url.endsWith("/get_session_info") -> result(request, mapOf("uid" to 7,
            "user_context" to mapOf("lang" to "es_ES", "allowed_company_ids" to listOf(1))))
        request.url.contains("/web/dataset/call_kw/") -> result(request, emptyList<Any?>())
        else -> result(request, true)
    }

    @Test fun callbackCompletesSessionAndModelsUseCookiesInsteadOfPasswordRpc(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/totp") && it.method == "POST") {
                assertEquals("csrf_token=csrf-first&totp_token=012345", it.body)
                assertTrue(it.headers["Content-Type"]!!.startsWith("application/x-www-form-urlencoded"))
            }
            responder(it)
        }
        var prompts = 0
        val client = OdooClient.authenticate("https://example.test/prefix", "db", "user", "secret",
            transport = transport, context = mapOf("tz" to "UTC"), onTwoFactorCode = { prompts++; "012 345" })
        assertEquals(7, client.userId)
        assertEquals("es_ES", client.context["lang"])
        assertEquals("UTC", client.context["tz"])
        assertFalse(client.getControllers().twoFactorRequired)
        client.getModel("res.partner").searchRead(fields = listOf("name"))
        val model = transport.requests.last()
        assertEquals("https://example.test/prefix/web/dataset/call_kw/res.partner/search_read", model.url)
        val params = stringMap(stringMap(JsonCodec.decode(model.body!!))["params"])
        assertEquals(client.context, stringMap(params["kwargs"])["context"])
        assertFalse(params.containsKey("context"))
        assertFalse(model.body.contains("secret"))
        assertEquals(true, client.getControllers().jsonRpc("/private", auth = "user"))
        assertEquals(7, client.checkLogin())
        assertEquals(1, prompts)
        assertEquals(1, transport.requests.count { it.url.endsWith("/authenticate") })
        assertFalse(transport.requests.any { it.url.endsWith("/jsonrpc") })
    }

    @Test fun pendingChallengeCanBeCompletedWithoutRepeatingPasswordLogin(): Unit = runBlocking {
        val transport = FakeTransport(::responder)
        val routes = routes(transport)
        assertThrowsSuspend<TwoFactorRequiredException> { routes.jsonRpc("/write", auth = "user") }
        assertTrue(routes.twoFactorRequired)
        assertNull(routes.userId)
        assertEquals(2, transport.requests.size)
        assertEquals(7, routes.completeTwoFactor("012345"))
        assertEquals(true, routes.jsonRpc("/write", auth = "user"))
        assertEquals(1, transport.requests.count { it.url.endsWith("/write") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/authenticate") })
    }

    @Test fun rejectedCodeAllowsExplicitRetryWithRefreshedCsrf(): Unit = runBlocking {
        var attempts = 0
        val transport = FakeTransport {
            if (it.url.endsWith("/totp") && it.method == "POST") {
                if (++attempts == 1) form("csrf-next") else {
                    assertEquals("csrf_token=csrf-next&totp_token=012346", it.body)
                    HttpResponse(302, "")
                }
            } else responder(it)
        }
        val routes = routes(transport)
        assertThrowsSuspend<TwoFactorRequiredException> { routes.authenticate() }
        val error = assertThrowsSuspend<InvalidTwoFactorCodeException> { routes.completeTwoFactor("012345") }
        assertFalse(error.toString().contains("012345"))
        assertTrue(routes.twoFactorRequired)
        assertNull(routes.userId)
        assertEquals(7, routes.completeTwoFactor("012346"))
        assertEquals(2, attempts)
    }

    @Test fun invalidCodeAndMissingChallengeDoNotSendRequests(): Unit = runBlocking {
        val transport = FakeTransport(::responder)
        val routes = routes(transport)
        assertThrowsSuspend<AuthenticationException> { routes.completeTwoFactor("123456") }
        assertTrue(transport.requests.isEmpty())
        assertThrowsSuspend<TwoFactorRequiredException> { routes.authenticate() }
        for (code in listOf("", "12345", "1234567", "abcdef", "１２３４５６")) {
            assertThrowsSuspend<IllegalArgumentException> { routes.completeTwoFactor(code) }
        }
        assertEquals(2, transport.requests.size)
    }

    @Test fun normalLoginDoesNotRequestTwoFactorCodeAndCheckLoginValidatesSession(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/authenticate")) result(it, mapOf("uid" to 7)) else responder(it)
        }
        val client = OdooClient.authenticate("https://example.test", "db", "user", "secret",
            transport = transport, onTwoFactorCode = { error("Unexpected prompt") })
        assertEquals(7, client.checkLogin())
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.last().url.endsWith("/get_session_info"))
    }

    @Test fun redirectsNeverProveLoginAndUnsupportedMfaDoesNotRequestCode(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/get_session_info")) result(it, mapOf("uid" to false)) else responder(it)
        }
        val routes = routes(transport)
        assertThrowsSuspend<AuthenticationException> { routes.authenticate(onTwoFactorCode = { "123456" }) }
        assertNull(routes.userId)
        assertFalse(routes.twoFactorRequired)
        val unsupported = routes(FakeTransport {
            if (it.method == "GET") HttpResponse(200, "<form>Passkey login</form>") else responder(it)
        })
        assertThrowsSuspend<AuthenticationException> { unsupported.authenticate(onTwoFactorCode = { error("Unexpected prompt") }) }
    }

    @Test fun cancelledPromptAndRateLimitDoNotReplayLoginOrSubmitCodeAgain(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/totp") && it.method == "POST") HttpResponse(429, "Too many attempts") else responder(it)
        }
        val routes = routes(transport)
        assertThrowsSuspend<CancellationException> { routes.authenticate(onTwoFactorCode = { throw CancellationException() }) }
        assertTrue(routes.twoFactorRequired)
        val error = assertThrowsSuspend<HttpException> { routes.completeTwoFactor("123456") }
        assertEquals(429, error.statusCode)
        assertEquals(1, transport.requests.count { it.url.endsWith("/authenticate") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/totp") && it.method == "POST" })
    }

    @Test fun trustedDeviceAndMultiDatabaseSessionSelectionStayOnConfiguredServer(): Unit = runBlocking {
        var gets = 0
        val transport = FakeTransport {
            when {
                it.url.endsWith("/totp") && it.method == "GET" -> if (++gets == 1) HttpResponse(404, "") else HttpResponse(303, "")
                it.url.contains("/web/login?db=") -> HttpResponse(302, "")
                else -> responder(it)
            }
        }
        assertEquals(7, routes(transport).authenticate(onTwoFactorCode = { error("Unexpected prompt") }))
        assertTrue(transport.requests.any { it.url == "https://example.test/prefix/web/login?db=db" })
        assertTrue(transport.requests.all { it.url.startsWith("https://example.test/prefix/") })
    }

    @Test fun expiredModelSessionClearsUidAndDoesNotReplayTheOperation(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.url.endsWith("/authenticate")) result(it, mapOf("uid" to 7)) else HttpResponse(200,
                """{"jsonrpc":"2.0","id":null,"error":{"code":100,"data":{"name":"odoo.http.SessionExpiredException"}}}""")
        }
        val client = OdooClient.authenticate("https://example.test", "db", "user", "secret", transport = transport)
        assertThrowsSuspend<AuthenticationException> { client.getModel("res.partner").write(listOf(1), mapOf("name" to "Changed")) }
        assertNull(client.userId)
        assertNull(client.getControllers().userId)
        assertEquals(2, transport.requests.size)
    }

    @Test fun csrfParserHandlesAttributeOrderAndEscapedValues() {
        assertEquals("a&b", OdooTotpForm.csrfToken("""<INPUT VALUE="a&amp;b" NAME='csrf_token'><input name='totp_token'>"""))
        assertNull(OdooTotpForm.csrfToken("""<input name='csrf_token' value='token'>"""))
        assertNull(OdooTotpForm.csrfToken("""<input name='totp_token'><input name='csrf_token'>"""))
    }

    @Test fun realTransportRetainsPartialCookieAndUsesRotatedSessionForModels(): Unit = runBlocking {
        val server = TestHttpServer()
        var followedRedirect = false
        fun reply(exchange: TestHttpServer.Exchange, value: Any?) {
            val request = HttpRequest("unused", body = exchange.requestBody.bufferedReader().readText())
            exchange.responseBody.write(result(request, value).body.toByteArray())
        }
        server.createContext("/web/session/authenticate") {
            it.responseHeaders.add("Set-Cookie", "session_id=partial; Path=/; HttpOnly")
            reply(it, mapOf("uid" to null))
        }
        server.createContext("/web/login/totp") {
            assertTrue(it.requestHeaders.getFirst("Cookie").orEmpty().contains("session_id=partial"))
            val body = it.requestBody.bufferedReader().readText()
            if (body.isEmpty()) it.responseBody.write(form("csrf&amp;value").body.toByteArray()) else {
                assertEquals("csrf_token=csrf%26value&totp_token=012345", body)
                it.responseHeaders.add("Set-Cookie", "session_id=verified; Path=/; HttpOnly")
                it.responseHeaders.add("Location", "/should-not-follow")
                it.sendResponseHeaders(303, -1)
            }
        }
        server.createContext("/web/session/get_session_info") {
            assertTrue(it.requestHeaders.getFirst("Cookie").orEmpty().contains("session_id=verified"))
            reply(it, mapOf("uid" to 7, "user_context" to mapOf("lang" to "es_ES")))
        }
        server.createContext("/web/dataset/call_kw/res.partner/search_read") {
            assertTrue(it.requestHeaders.getFirst("Cookie").orEmpty().contains("session_id=verified"))
            reply(it, listOf(mapOf("id" to 1, "name" to "Contact")))
        }
        server.createContext("/should-not-follow") { followedRedirect = true }
        server.start()
        try {
            val client = OdooClient.authenticate("http://127.0.0.1:${server.address.port}", "db", "user", "secret",
                onTwoFactorCode = { "012345" })
            assertEquals("Contact", client.getModel("res.partner").searchRead().single()["name"])
            assertFalse(followedRedirect)
        } finally { server.stop(0) }
    }
}
