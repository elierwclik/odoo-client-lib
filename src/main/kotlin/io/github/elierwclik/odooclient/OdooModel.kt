/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A decoded Odoo record; empty field values may be false instead of null. */
typealias OdooRecord = Map<String, Any?>
/** Domain predicates and prefix operators, represented as Kotlin lists and strings. */
typealias OdooDomain = List<Any?>

/** Proxy for public methods of one Odoo model; all network methods are suspending. */
class OdooModel internal constructor(private val client: OdooClient, val name: String) {
    private data class MethodInfo(val parameters: List<String>, val modelMethod: Boolean)
    private val introspectionMutex = Mutex()
    private var methods: Map<String, MethodInfo>? = null

    /** Calls any public server method. JSON-2 positional calls use cached /doc-bearer introspection. */
    suspend fun call(
        method: String, args: List<Any?> = emptyList(), kwargs: Map<String, Any?> = emptyMap(),
    ): Any? {
        validateName(method)
        require(method != "browse") { "browse() returns server-side objects; use read()" }
        if (client.protocol != OdooProtocol.JSON2 || args.isEmpty()) {
            return client.callModel(name, method, args, kwargs)
        }
        val info = introspect()[method]
            ?: throw IllegalArgumentException("Method is not in Odoo's introspection; use named arguments")
        val named = kwargs.toMutableMap()
        var offset = 0
        if (!info.modelMethod && "ids" !in named) {
            named["ids"] = if (args[0] is Number) listOf(args[0]) else args[0]
            offset = 1
        }
        require(args.size - offset <= info.parameters.size) { "Too many positional arguments" }
        args.drop(offset).forEachIndexed { index, value ->
            val parameter = info.parameters[index]
            require(parameter !in named) { "Argument supplied twice: $parameter" }
            named[parameter] = value
        }
        return client.callModel(name, method, emptyList(), named)
    }

    private suspend fun introspect(): Map<String, MethodInfo> = introspectionMutex.withLock {
        methods?.let { return@withLock it }
        val doc = stringMap(client.requestJson2("${client.baseUrl}/doc-bearer/$name.json"))
        val metadata = stringMap(doc["methods"])
        val parsed = metadata.mapValues { (_, value) ->
            val method = stringMap(value)
            MethodInfo(stringMap(method["parameters"]).keys.toList(), (method["api"] as? List<*>)?.contains("model") == true)
        }
        methods = parsed
        parsed
    }

    private suspend fun invoke(
        method: String, args: List<Any?>, rpcKwargs: Map<String, Any?>,
        json2Params: Map<String, Any?>,
    ): Any? = if (client.protocol == OdooProtocol.JSON2) {
        client.callModel(name, method, emptyList(), json2Params)
    } else {
        client.callModel(name, method, args, rpcKwargs)
    }

    private fun searchParams(
        domain: OdooDomain, offset: Int, limit: Int?, order: String?, context: Map<String, Any?>,
    ): Map<String, Any?> {
        require(offset >= 0 && (limit == null || limit > 0)) { "Offset must be non-negative and limit positive" }
        return buildMap {
            put("domain", domain); put("offset", offset); put("context", context)
            limit?.let { put("limit", it) }
            order?.let { put("order", it) }
        }
    }

    /** Returns IDs matching [domain], with optional pagination, ordering and context. */
    suspend fun search(
        domain: OdooDomain = emptyList(), offset: Int = 0, limit: Int? = null,
        order: String? = null, context: Map<String, Any?> = emptyMap(),
    ): List<Int> {
        val params = searchParams(domain, offset, limit, order, context)
        val result = invoke("search", listOf(domain), params - "domain", params)
        return listResult(result).map(::recordId)
    }

    /** Returns the number of matching records, including zero. */
    suspend fun searchCount(domain: OdooDomain = emptyList(), context: Map<String, Any?> = emptyMap()): Int =
        recordId(invoke("search_count", listOf(domain), mapOf("context" to context), mapOf("domain" to domain, "context" to context)), allowZero = true)

    /** Always returns a list in the requested ID order, including when one ID is requested. */
    suspend fun read(
        ids: List<Int>, fields: List<String> = emptyList(), context: Map<String, Any?> = emptyMap(),
    ): List<OdooRecord> {
        require(ids.all { it > 0 }) { "Record IDs must be positive" }
        if (ids.isEmpty()) return emptyList()
        val params = mapOf("ids" to ids, "fields" to fields, "context" to context)
        val result = invoke("read", listOf(ids, fields), mapOf("context" to context), params)
        val rows = listResult(result).map(::stringMap)
        if (rows.any { "id" !in it }) throw ProtocolException("Odoo read result is missing IDs")
        val index = rows.associateBy { recordId(it["id"]) }
        return ids.mapNotNull(index::get)
    }

    /** Reads one record, returning null if absent; permission errors still propagate. */
    suspend fun read(id: Int, fields: List<String> = emptyList(), context: Map<String, Any?> = emptyMap()): OdooRecord? =
        read(listOf(id), fields, context).firstOrNull()

    /** Uses the server's single search_read transaction. */
    suspend fun searchRead(
        domain: OdooDomain = emptyList(), fields: List<String> = emptyList(), offset: Int = 0,
        limit: Int? = null, order: String? = null, context: Map<String, Any?> = emptyMap(),
    ): List<OdooRecord> {
        val params = searchParams(domain, offset, limit, order, context) + ("fields" to fields)
        return listResult(invoke("search_read", listOf(domain), params - "domain", params)).map(::stringMap)
    }

    /** Creates one record and returns its ID. This writes to the server. */
    suspend fun create(values: OdooRecord, context: Map<String, Any?> = emptyMap()): Int {
        val result = invoke("create", listOf(values), mapOf("context" to context), mapOf("vals_list" to listOf(values), "context" to context))
        return if (result is List<*>) recordId(result.singleOrNull()) else recordId(result)
    }

    /** Creates multiple records in one call, when supported by the server. */
    suspend fun createMany(values: List<OdooRecord>, context: Map<String, Any?> = emptyMap()): List<Int> {
        if (values.isEmpty()) return emptyList()
        val result = invoke("create", listOf(values), mapOf("context" to context), mapOf("vals_list" to values, "context" to context))
        return listResult(result).map(::recordId)
    }

    /** Updates all [ids] with [values]. An empty ID list succeeds without a request. */
    suspend fun write(ids: List<Int>, values: OdooRecord, context: Map<String, Any?> = emptyMap()): Boolean {
        require(ids.all { it > 0 }) { "Record IDs must be positive" }
        if (ids.isEmpty()) return true
        return booleanResult(invoke("write", listOf(ids, values), mapOf("context" to context), mapOf("ids" to ids, "vals" to values, "context" to context)))
    }

    /** Deletes records; an empty ID list succeeds without a request. */
    suspend fun unlink(ids: List<Int>, context: Map<String, Any?> = emptyMap()): Boolean {
        require(ids.all { it > 0 }) { "Record IDs must be positive" }
        if (ids.isEmpty()) return true
        return booleanResult(invoke("unlink", listOf(ids), mapOf("context" to context), mapOf("ids" to ids, "context" to context)))
    }

    /** Returns field metadata. Empty [fields] or [attributes] requests all of them. */
    suspend fun fieldsGet(
        fields: List<String> = emptyList(), attributes: List<String> = emptyList(), context: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val params = mapOf("allfields" to fields, "attributes" to attributes, "context" to context)
        return stringMap(invoke("fields_get", emptyList(), params, params))
    }

    private fun listResult(value: Any?): List<*> = value as? List<*> ?: throw ProtocolException("Expected an Odoo list")
    private fun booleanResult(value: Any?): Boolean = value as? Boolean ?: throw ProtocolException("Expected an Odoo boolean")
    private fun recordId(value: Any?, allowZero: Boolean = false): Int {
        val number = value as? Number ?: throw ProtocolException("Expected an Odoo integer")
        val id = try { number.toString().toBigDecimal().intValueExact() }
        catch (error: IllegalArgumentException) { throw ProtocolException("Invalid Odoo integer", error) }
        catch (error: ArithmeticException) { throw ProtocolException("Invalid Odoo integer", error) }
        if (id < (if (allowZero) 0 else 1)) throw ProtocolException("Invalid Odoo integer")
        return id
    }
}
