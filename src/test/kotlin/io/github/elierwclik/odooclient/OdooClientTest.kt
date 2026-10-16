/* Derived from Odoo's odoo-client-lib; SPDX-License-Identifier: BSD-3-Clause.
 * Copyright notices and license: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OdooClientTest {
    private class FakeTransport(private val responder: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return responder(request)
        }
    }

    private fun rpcResponse(request: HttpRequest, result: Any?): HttpResponse {
        val payload = stringMap(JsonCodec.decode(requireNotNull(request.body)))
        return HttpResponse(200, JsonCodec.encode(mapOf("jsonrpc" to "2.0", "id" to payload["id"], "result" to result)))
    }

    private fun params(request: HttpRequest): Map<String, Any?> =
        stringMap(stringMap(JsonCodec.decode(requireNotNull(request.body)))["params"])

    private fun rpc(transport: HttpTransport, userId: Int? = null): OdooClient =
        OdooClient.rpc("https://example.test/odoo/", "db", "user", "secret", userId = userId, transport = transport)

    @Test fun jsonRpcLoginAndModelCallsUseExecuteKw(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            rpcResponse(request, if (params(request)["service"] == "common") 7 else listOf(3, 4))
        }
        val client = rpc(transport)
        val model = client.getModel("res.partner")
        val domain = listOf(listOf("active", "=", true))
        assertEquals(listOf(3, 4), model.search(domain, limit = 10, context = mapOf("lang" to "es_ES")))
        model.search()
        assertEquals(3, transport.requests.size)
        assertEquals(listOf("db", "user", "secret"), params(transport.requests[0])["args"])
        assertEquals("https://example.test/odoo/jsonrpc", transport.requests[0].url)
        val call = params(transport.requests[1])
        assertEquals("execute_kw", call["method"])
        val args = call["args"] as List<*>
        assertEquals(listOf("db", 7, "secret", "res.partner", "search"), args.take(5))
        assertEquals(listOf(domain), args[5])
        assertEquals(10, (args[6] as Map<*, *>)["limit"])
        assertSame(model, client.getModel("res.partner"))
    }

    @Test fun invalidLoginIsNotCached(): Unit = runBlocking {
        var attempts = 0
        val transport = FakeTransport { rpcResponse(it, if (++attempts == 1) false else 8) }
        val client = rpc(transport)
        assertThrowsSuspend<AuthenticationException> { client.checkLogin() }
        assertNull(client.userId)
        assertEquals(8, client.checkLogin(false))
        assertEquals(8, client.checkLogin(false))
        assertEquals(2, attempts)
    }

    @Test fun forcedFailedLoginClearsCachedId(): Unit = runBlocking {
        val client = rpc(FakeTransport { rpcResponse(it, false) }, userId = 9)
        assertThrowsSuspend<AuthenticationException> { client.checkLogin() }
        assertNull(client.userId)
    }

    @Test fun concurrentModelCallsOnlyLoginOnce(): Unit = runBlocking {
        var logins = 0
        val client = rpc(FakeTransport {
            rpcResponse(it, if (params(it)["service"] == "common") { logins++; 7 } else emptyList<Int>())
        })
        (1..20).map { async { client.getModel("res.partner").search() } }.awaitAll()
        assertEquals(1, logins)
    }

    @Test fun readPreservesOrderDuplicatesAndMissingRecords(): Unit = runBlocking {
        val client = rpc(FakeTransport {
            rpcResponse(it, listOf(mapOf("id" to 1, "name" to "First"), mapOf("id" to 3, "name" to "Third")))
        }, userId = 7)
        val model = client.getModel("res.partner")
        assertEquals(listOf(3, 1, 3), model.read(listOf(3, 2, 1, 3)).map { it["id"] })
        assertEquals("First", model.read(1)?.get("name"))
        assertNull(model.read(2))
    }

    @Test fun emptyRecordSetsDoNotHitServer(): Unit = runBlocking {
        val transport = FakeTransport { error("Unexpected request") }
        val model = rpc(transport).getModel("res.partner")
        assertEquals(emptyList<OdooRecord>(), model.read(emptyList()))
        assertEquals(emptyList<Int>(), model.createMany(emptyList()))
        assertTrue(model.write(emptyList(), emptyMap()))
        assertTrue(model.unlink(emptyList()))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun searchReadUsesOneCallAndCarriesContext(): Unit = runBlocking {
        val transport = FakeTransport { rpcResponse(it, listOf(mapOf("id" to 1))) }
        val model = rpc(transport, userId = 7).getModel("res.partner")
        assertEquals(1, model.searchRead(fields = listOf("name"), context = mapOf("lang" to "es_ES")).size)
        assertEquals(1, transport.requests.size)
        val args = params(transport.requests.single())["args"] as List<*>
        assertEquals("search_read", args[4])
        assertEquals(mapOf("lang" to "es_ES"), (args[6] as Map<*, *>)["context"])
    }

    @Test fun rpcCrudAndMetadataPayloads(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            val args = params(request)["args"] as List<*>
            rpcResponse(request, when (args[4]) {
                "create" -> if ((args[5] as List<*>)[0] is List<*>) listOf(11, 12) else 11
                "write", "unlink" -> true
                "search_count" -> 0
                "fields_get" -> mapOf("name" to mapOf("type" to "char"))
                else -> error("Unexpected method")
            })
        }
        val model = rpc(transport, 7).getModel("res.partner")
        assertEquals(11, model.create(mapOf("name" to "A")))
        assertEquals(listOf(11, 12), model.createMany(listOf(mapOf("name" to "A"), mapOf("name" to "B"))))
        assertTrue(model.write(listOf(11), mapOf("name" to "C")))
        assertTrue(model.unlink(listOf(11)))
        assertEquals(0, model.searchCount())
        assertTrue("name" in model.fieldsGet())
    }

    @Test fun serviceVersionDoesNotAuthenticate(): Unit = runBlocking {
        val transport = FakeTransport { rpcResponse(it, mapOf("server_version" to "19.0")) }
        assertEquals(mapOf("server_version" to "19.0"), rpc(transport).getService("common").call("version"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun jsonRpcFaultRetainsStructuredError(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            val id = stringMap(JsonCodec.decode(request.body!!))["id"]
            HttpResponse(200, JsonCodec.encode(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to 200, "message" to "Denied"))))
        }
        val error = assertThrowsSuspend<RemoteException> { rpc(transport, 7).getModel("res.partner").search() }
        assertEquals(200, error.code)
        assertEquals("Denied", (error.error as Map<*, *>)["message"])
        assertFalse(error.toString().contains("secret"))
    }

    @Test fun wrongJsonRpcIdIsRejected(): Unit = runBlocking {
        val transport = FakeTransport { HttpResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":9999,\"result\":7}") }
        assertThrowsSuspend<ProtocolException> { rpc(transport).checkLogin() }
    }

    @Test fun malformedResponsesAndHttpErrorsAreRejected(): Unit = runBlocking {
        assertThrowsSuspend<ProtocolException> { rpc(FakeTransport { HttpResponse(200, "<html>oops</html>") }).checkLogin() }
        val error = assertThrowsSuspend<HttpException> { rpc(FakeTransport { HttpResponse(503, "unavailable") }).checkLogin() }
        assertEquals(503, error.statusCode)
        assertThrowsSuspend<AuthenticationException> { rpc(FakeTransport { HttpResponse(401, "unauthorized") }).checkLogin() }
    }

    @Test fun json2CrudUsesNamedParametersWithoutIntrospection(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            val result: Any = when (request.url.substringAfterLast('/')) {
                "search" -> listOf(1, 2)
                "search_count" -> 2
                "search_read", "read" -> listOf(mapOf("id" to 2), mapOf("id" to 1))
                "create" -> listOf(3)
                "write", "unlink" -> true
                "fields_get" -> mapOf("name" to mapOf("type" to "char"))
                else -> error("Unexpected method")
            }
            HttpResponse(200, JsonCodec.encode(result))
        }
        val model = OdooClient.json2("https://example.test", "api-secret", "db", transport).getModel("res.partner")
        assertEquals(listOf(1, 2), model.search())
        assertEquals(2, model.searchCount())
        assertEquals(2, model.searchRead().size)
        assertEquals(listOf(1, 2), model.read(listOf(1, 2)).map { it["id"] })
        assertEquals(3, model.create(mapOf("name" to "A")))
        assertEquals(listOf(3), model.createMany(listOf(mapOf("name" to "B"))))
        assertTrue(model.write(listOf(3), mapOf("name" to "C")))
        assertTrue(model.unlink(listOf(3)))
        assertTrue("name" in model.fieldsGet())
        transport.requests.forEach {
            assertEquals("POST", it.method)
            assertEquals("Bearer api-secret", it.headers["Authorization"])
            assertEquals("db", it.headers["X-Odoo-Database"])
            assertTrue(it.url.startsWith("https://example.test/json/2/res.partner/"))
        }
        val create = stringMap(JsonCodec.decode(transport.requests[4].body!!))
        assertEquals(listOf(mapOf("name" to "A")), create["vals_list"])
        val write = stringMap(JsonCodec.decode(transport.requests[6].body!!))
        assertEquals(listOf(3), write["ids"])
        assertEquals(mapOf("name" to "C"), write["vals"])
    }

    @Test fun json2CustomNamedCallsAndOptionalDatabase(): Unit = runBlocking {
        val transport = FakeTransport { HttpResponse(200, "false") }
        val model = OdooClient.json2("https://example.test/prefix", "key", transport = transport).getModel("custom.model")
        assertEquals(false, model.call("action", kwargs = mapOf("ids" to listOf(8), "value" to 9)))
        val request = transport.requests.single()
        assertEquals("https://example.test/prefix/json/2/custom.model/action", request.url)
        assertFalse(request.headers.containsKey("X-Odoo-Database"))
    }

    @Test fun json2PositionalCallsCacheIntrospection(): Unit = runBlocking {
        val transport = FakeTransport {
            if (it.method == "GET") HttpResponse(200, """{"methods":{"read":{"parameters":{"fields":{}},"api":[]},"search":{"parameters":{"domain":{}},"api":["model"]}}}""")
            else HttpResponse(200, "[]")
        }
        val model = OdooClient.json2("https://example.test", "key", transport = transport).getModel("res.partner")
        model.call("read", listOf(3, listOf("name")))
        model.call("search", listOf(emptyList<Any?>()))
        model.call("read", listOf(listOf(4), listOf("name")))
        assertEquals(1, transport.requests.count { it.method == "GET" })
        assertEquals("https://example.test/doc-bearer/res.partner.json", transport.requests.first().url)
        assertEquals(mapOf("ids" to listOf(3), "fields" to listOf("name")), JsonCodec.decode(transport.requests[1].body!!))
        assertThrowsSuspend<IllegalArgumentException> { model.call("search", listOf(emptyList<Any?>(), 99)) }
        assertThrowsSuspend<IllegalArgumentException> { model.call("search", listOf(emptyList<Any?>()), mapOf("domain" to emptyList<Any?>())) }
    }

    @Test fun json2ContextIsCachedAndCanBeRefreshed(): Unit = runBlocking {
        val transport = FakeTransport { HttpResponse(200, "{\"uid\":7,\"lang\":\"es_ES\"}") }
        val client = OdooClient.json2("https://example.test", "key", transport = transport)
        assertEquals(7, client.checkLogin())
        client.getUserContext()
        client.checkLogin(false)
        assertEquals(1, transport.requests.size)
        client.checkLogin(true)
        assertEquals(2, transport.requests.size)
    }

    @Test fun json2ErrorsPreserveStructuredFault(): Unit = runBlocking {
        val client = OdooClient.json2("https://example.test", "key", transport = FakeTransport {
            HttpResponse(422, "{\"name\":\"odoo.exceptions.ValidationError\",\"message\":\"Invalid field\"}")
        })
        val error = assertThrowsSuspend<RemoteException> { client.getModel("res.partner").search() }
        assertEquals(422, error.code)
        val unauthorized = OdooClient.json2("https://example.test", "key", transport = FakeTransport { HttpResponse(401, "{}") })
        assertThrowsSuspend<AuthenticationException> { unauthorized.checkLogin() }
    }

    @Test fun xmlRpcClientUsesLoginAndObjectEndpoints(): Unit = runBlocking {
        val transport = FakeTransport { request ->
            HttpResponse(200, if (request.url.endsWith("/common")) xmlResult("<int>7</int>")
            else xmlResult("<array><data><value><int>2</int></value></data></array>"))
        }
        val client = OdooClient.rpc("https://example.test", "db", "user", "secret", OdooProtocol.XML_RPC, transport = transport)
        assertEquals(listOf(2), client.getModel("res.partner").search())
        assertEquals("https://example.test/xmlrpc/common", transport.requests[0].url)
        assertEquals("https://example.test/xmlrpc/object", transport.requests[1].url)
        assertTrue(transport.requests[1].body!!.contains("<methodName>execute_kw</methodName>"))
    }

    @Test fun allMigrationProtocolNamesSelectSchemeAndEndpoint(): Unit = runBlocking {
        for (name in listOf("xmlrpc", "xmlrpcs", "jsonrpc", "jsonrpcs", "json2", "json2s")) {
            val transport = FakeTransport {
                when {
                    name.startsWith("xml") -> HttpResponse(200, xmlResult("<int>7</int>"))
                    name.startsWith("json2") -> HttpResponse(200, "{\"uid\":7}")
                    else -> rpcResponse(it, 7)
                }
            }
            OdooClient.getConnection("example.test", "db", "user", "secret", name, 8069, transport = transport).checkLogin()
            val scheme = if (name.endsWith('s')) "https" else "http"
            assertTrue(transport.requests.single().url.startsWith("$scheme://example.test:8069/"))
        }
    }

    @Test fun invalidInputFailsBeforeNetwork(): Unit = runBlocking {
        val client = rpc(FakeTransport { error("Unexpected request") })
        assertThrows(IllegalArgumentException::class.java) { client.getModel("res.partner/other") }
        assertThrowsSuspend<IllegalArgumentException> { client.getModel("res.partner").call("browse") }
        assertThrowsSuspend<IllegalArgumentException> { client.getModel("res.partner").search(limit = 0) }
        assertThrowsSuspend<IllegalArgumentException> { client.getModel("res.partner").read(-1) }
        assertThrows(IllegalArgumentException::class.java) { OdooClient.json2("https://user:pass@example.test", "key") }
        assertThrows(IllegalArgumentException::class.java) { OdooClient.json2("https://example.test?x=1", "key") }
        assertThrows(IllegalArgumentException::class.java) { OdooClient.json2("https://example.test", "key\r\nInjected") }
    }

    @Test fun modelContextMergesDefaultsAndPerCallOverridesForRpcAndJson2(): Unit = runBlocking {
        for (json2 in listOf(false, true)) {
            val transport = FakeTransport { if (json2) HttpResponse(200, "[]") else rpcResponse(it, emptyList<Int>()) }
            val defaults = mapOf("lang" to "es_ES", "tz" to "UTC", "allowed_company_ids" to listOf(2, 1))
            val client = if (json2) OdooClient.json2("https://example.test", "key", transport = transport, context = defaults)
                else OdooClient.rpc("https://example.test", "db", "user", "secret", userId = 7,
                    transport = transport, context = defaults)
            client.updateContext(mapOf("active_test" to false))
            client.getModel("res.partner").search(context = mapOf("lang" to "fr_FR"))
            val payload = if (json2) stringMap(JsonCodec.decode(transport.requests.last().body!!))
                else stringMap((params(transport.requests.last())["args"] as List<*>)[6])
            assertEquals(defaults + mapOf("lang" to "fr_FR", "active_test" to false), payload["context"])
            assertEquals("es_ES", client.context["lang"])
            client.getModel("res.partner").call("custom_method")
            val custom = if (json2) stringMap(JsonCodec.decode(transport.requests.last().body!!))
                else stringMap((params(transport.requests.last())["args"] as List<*>)[6])
            assertEquals(client.context, custom["context"])
        }
    }

    @Test fun refreshedUserContextPreservesAppOverridesAndSnapshotsAreDetached(): Unit = runBlocking {
        val companies = mutableListOf(2, 1)
        val transport = FakeTransport { rpcResponse(it, mapOf("lang" to "en_US", "tz" to "UTC")) }
        val client = OdooClient.rpc("https://example.test", "db", "user", "secret", userId = 7,
            transport = transport, context = mapOf("lang" to "es_ES", "allowed_company_ids" to companies))
        companies.clear()
        assertEquals(listOf(2, 1), client.context["allowed_company_ids"])
        @Suppress("UNCHECKED_CAST")
        (client.context["allowed_company_ids"] as MutableList<Int>).clear()
        assertEquals(listOf(2, 1), client.context["allowed_company_ids"])
        client.getUserContext(force = true)
        assertEquals("es_ES", client.context["lang"])
        assertEquals("UTC", client.context["tz"])
        assertEquals(listOf(2, 1), client.context["allowed_company_ids"])
    }

    private fun xmlResult(value: String): String = "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"
}

internal suspend inline fun <reified T : Throwable> assertThrowsSuspend(noinline block: suspend () -> Unit): T {
    try { block() } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.name}, got ${error.javaClass.name}", error)
    }
    throw AssertionError("Expected ${T::class.java.name}")
}
