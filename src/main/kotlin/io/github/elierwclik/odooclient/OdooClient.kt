/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Odoo wire protocol; HTTP or HTTPS is selected by the client's base URL. */
enum class OdooProtocol { XML_RPC, JSON_RPC, JSON2 }

/** One authenticated connection; credentials are kept in memory and never logged. */
class OdooClient private constructor(
    baseUrl: String,
    val protocol: OdooProtocol,
    private val database: String?,
    private val login: String?,
    private val secret: String,
    initialUserId: Int?,
    private val transport: HttpTransport,
    private val contextState: OdooContext = OdooContext(),
) {
    internal val baseUrl: String = baseUrl.trimEnd('/')
    private val authMutex = Mutex()
    private val contextMutex = Mutex()
    private val requestIds = AtomicLong()
    private val models = ConcurrentHashMap<String, OdooModel>()
    private var sessionAuthentication = false
    private val controllerClient by lazy {
        check(protocol != OdooProtocol.JSON2) { "Create OdooClient.controllers() with web-login credentials for session routes" }
        OdooControllerClient(this.baseUrl, database, login, secret, transport, contextState)
    }
    @Volatile var userId: Int? = initialUserId
        private set

    init {
        val uri = URI(this.baseUrl)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Provide a valid HTTP(S) base URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Base URL must not include credentials, query or fragment" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port" }
        require(secret.isNotBlank()) { "Provide a password or API key" }
        require(initialUserId == null || initialUserId > 0) { "User ID must be positive" }
        if (protocol != OdooProtocol.JSON2) {
            require(!database.isNullOrBlank() && !login.isNullOrBlank()) { "RPC requires database and login" }
        }
        require(database == null || ('\r' !in database && '\n' !in database)) { "Invalid database name" }
        require('\r' !in secret && '\n' !in secret) { "Invalid credential" }
    }

    companion object {
        /**
         * Signs in immediately with a web-login password and returns a reusable client
         * for model calls and custom controllers, both using the same web session.
         * [onTwoFactorCode] is invoked only when Odoo requests a TOTP authenticator code.
         * Use [rpc] or [json2] when authenticating with an API key instead.
         */
        suspend fun authenticate(
            baseUrl: String, database: String, login: String, password: String,
            protocol: OdooProtocol = OdooProtocol.JSON_RPC,
            transport: HttpTransport = UrlConnectionTransport(),
            context: Map<String, Any?> = emptyMap(),
            onTwoFactorCode: (suspend () -> String)? = null,
        ): OdooClient {
            val client = rpc(baseUrl, database, login, password, protocol, transport = transport, context = context)
            client.sessionAuthentication = true
            client.userId = client.getControllers().authenticate(onTwoFactorCode = onTwoFactorCode)
            return client
        }

        /** Creates a custom-route client. Omit credentials for public routes. */
        fun controllers(
            baseUrl: String, database: String? = null, login: String? = null,
            password: String? = null, transport: HttpTransport = UrlConnectionTransport(),
            context: Map<String, Any?> = emptyMap(),
        ): OdooControllerClient = OdooControllerClient(baseUrl, database, login, password, transport, OdooContext(context))

        /**
         * Creates a JSON-RPC or XML-RPC connection with lazy authentication.
         * [baseUrl] is the server root (optionally with a path prefix), without an RPC endpoint.
         * [password] may be an RPC API key. [userId] skips the initial login if known.
         * Use [json2] for JSON-2. Pass [transport] to customise HTTP or simulate responses.
         */
        fun rpc(
            baseUrl: String, database: String, login: String, password: String,
            protocol: OdooProtocol = OdooProtocol.JSON_RPC, userId: Int? = null,
            transport: HttpTransport = UrlConnectionTransport(),
            context: Map<String, Any?> = emptyMap(),
        ): OdooClient {
            require(protocol != OdooProtocol.JSON2) { "Use json2() for API-key authentication" }
            return OdooClient(baseUrl, protocol, database, login, password, userId, transport, OdooContext(context))
        }

        /** Creates a JSON-2 API-key connection; [database] is optional if the host selects it. */
        fun json2(
            baseUrl: String, apiKey: String, database: String? = null,
            transport: HttpTransport = UrlConnectionTransport(),
            context: Map<String, Any?> = emptyMap(),
        ): OdooClient = OdooClient(baseUrl, OdooProtocol.JSON2, database, null, apiKey, null, transport, OdooContext(context))

        /** Migration shortcut matching the original library's six protocol names and port 8069. */
        fun getConnection(
            hostname: String, database: String?, login: String? = null, password: String,
            protocol: String = "xmlrpc", port: Int = 8069, userId: Int? = null,
            transport: HttpTransport = UrlConnectionTransport(),
            context: Map<String, Any?> = emptyMap(),
        ): OdooClient {
            val type = when (protocol) {
                "xmlrpc", "xmlrpcs" -> OdooProtocol.XML_RPC
                "jsonrpc", "jsonrpcs" -> OdooProtocol.JSON_RPC
                "json2", "json2s" -> OdooProtocol.JSON2
                else -> throw IllegalArgumentException("Unknown Odoo protocol")
            }
            val scheme = if (protocol.endsWith('s')) "https" else "http"
            require(port in 1..65535) { "Invalid server port" }
            val url = URI(scheme, null, hostname, port, null, null, null).toString()
            return OdooClient(url, type, database, login, password, userId, transport, OdooContext(context))
        }
    }

    /** Effective user defaults plus app overrides (a detached snapshot). */
    val context: Map<String, Any?> get() = contextState.merged()

    /** Updates shared app defaults; per-call context takes precedence. */
    fun updateContext(values: Map<String, Any?>) { contextState.update(values) }

    /** Returns a cached model proxy without making a network request. */
    fun getModel(name: String): OdooModel {
        validateName(name)
        return models.computeIfAbsent(name) { OdooModel(this, it) }
    }

    /** Custom routes using this RPC client's transport and web-login credentials. */
    fun getControllers(): OdooControllerClient = controllerClient

    /** Returns an RPC service proxy; JSON-2 has model endpoints instead. */
    fun getService(name: String): OdooService {
        check(protocol != OdooProtocol.JSON2) { "JSON-2 has model endpoints; use getModel()" }
        validateName(name)
        return OdooService(this, name)
    }

    /**
     * Verifies authentication and returns the UID. When [force] is false, cached credentials
     * validation is reused. JSON-2 retrieves the UID from the user context.
     * @throws AuthenticationException if login fails or the server returns HTTP 401.
     */
    suspend fun checkLogin(force: Boolean = true): Int {
        if (sessionAuthentication) {
            val routes = getControllers()
            return try {
                (if (force) routes.checkSession() else routes.authenticate()).also { userId = it }
            } catch (error: AuthenticationException) {
                userId = null
                throw error
            }
        }
        if (protocol == OdooProtocol.JSON2) {
            val context = getUserContext(force)
            return (context["uid"] as? Number)?.toInt()?.takeIf { it > 0 }
                ?: throw ProtocolException("Odoo context did not contain a user ID")
        }
        return authMutex.withLock {
            if (!force) userId?.let { return@withLock it }
            userId = null
            val result = callService("common", "login", listOf(database, login, secret))
            val uid = (result as? Number)?.toInt()?.takeIf { it > 0 }
                ?: throw AuthenticationException()
            userId = uid
            uid
        }
    }

    /** Returns a copy of the cached user context, refreshing it from Odoo when [force] is true. */
    suspend fun getUserContext(force: Boolean = false): Map<String, Any?> = contextMutex.withLock {
        if (!force) contextState.server()?.let { return@withLock it }
        val context = stringMap(getModel("res.users").call("context_get"))
        contextState.setServer(context)
        context.toMap()
    }

    internal suspend fun callModel(model: String, method: String, args: List<Any?>, kwargs: Map<String, Any?>): Any? {
        validateName(method)
        val explicitContext = kwargs["context"]?.let(::stringMap).orEmpty()
        val effectiveContext = contextState.merged(explicitContext)
        val parameters = if (effectiveContext.isNotEmpty() || "context" in kwargs) {
            kwargs + ("context" to effectiveContext)
        } else kwargs
        if (sessionAuthentication) {
            return try {
                getControllers().callModel(model, method, args, parameters)
            } catch (error: AuthenticationException) {
                userId = null
                throw error
            }
        }
        if (protocol == OdooProtocol.JSON2) {
            require(args.isEmpty()) { "JSON-2 requires named arguments" }
            return requestJson2("$baseUrl/json/2/$model/$method", parameters)
        }
        val uid = checkLogin(false)
        return callService("object", "execute_kw", listOf(database, uid, secret, model, method, args, parameters))
    }

    internal suspend fun callService(service: String, method: String, args: List<Any?>): Any? {
        check(protocol != OdooProtocol.JSON2) { "Services are not available for JSON-2" }
        validateName(method)
        if (protocol == OdooProtocol.XML_RPC) {
            val response = transport.execute(HttpRequest(
                "$baseUrl/xmlrpc/$service", headers = mapOf("Content-Type" to "text/xml; charset=utf-8"),
                body = XmlRpcCodec.encode(method, args),
            ))
            checkStatus(response)
            return XmlRpcCodec.decode(response.body)
        }
        val id = requestIds.incrementAndGet()
        val body = JsonCodec.encode(mapOf(
            "jsonrpc" to "2.0", "method" to "call", "id" to id,
            "params" to mapOf("service" to service, "method" to method, "args" to args),
        ))
        val response = transport.execute(HttpRequest(
            "$baseUrl/jsonrpc", headers = mapOf("Content-Type" to "application/json; charset=utf-8"), body = body,
        ))
        checkStatus(response)
        val envelope = stringMap(JsonCodec.decode(response.body))
        if (envelope["jsonrpc"] != "2.0" || (envelope["id"] as? Number)?.toLong() != id) {
            throw ProtocolException("Invalid JSON-RPC version or request ID")
        }
        envelope["error"]?.let { error ->
            throw RemoteException(((error as? Map<*, *>)?.get("code") as? Number)?.toInt(), error)
        }
        if (!envelope.containsKey("result")) throw ProtocolException("JSON-RPC result is missing")
        return envelope["result"]
    }

    internal suspend fun requestJson2(url: String, body: Map<String, Any?>? = null): Any? {
        val headers = mutableMapOf(
            "Authorization" to "Bearer $secret", "Content-Type" to "application/json; charset=utf-8",
        )
        database?.takeIf { it.isNotBlank() }?.let { headers["X-Odoo-Database"] = it }
        val response = transport.execute(HttpRequest(url, if (body == null) "GET" else "POST", headers, body?.let(JsonCodec::encode)))
        checkStatus(response)
        return JsonCodec.decode(response.body)
    }

    private fun checkStatus(response: HttpResponse) {
        if (response.statusCode == 401) throw AuthenticationException()
        if (response.statusCode !in 200..299) {
            if (protocol == OdooProtocol.JSON2) {
                val error = try { JsonCodec.decode(response.body) } catch (_: ProtocolException) { null }
                if (error is Map<*, *>) throw RemoteException(response.statusCode, error)
            }
            throw HttpException(response.statusCode)
        }
    }
}

/** A remote RPC service. Calls do not automatically add authentication arguments. */
class OdooService internal constructor(private val client: OdooClient, val name: String) {
    /** Calls a service method with positional arguments and returns its decoded result. */
    suspend fun call(method: String, vararg args: Any?): Any? = client.callService(name, method, args.toList())
}

internal fun validateName(name: String) {
    require(Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(name)) { "Invalid Odoo model, service or method name" }
}

internal fun stringMap(value: Any?): Map<String, Any?> {
    if (value !is Map<*, *> || value.keys.any { it !is String }) throw ProtocolException("Expected an Odoo dictionary")
    return value.entries.associate { it.key as String to it.value }
}
