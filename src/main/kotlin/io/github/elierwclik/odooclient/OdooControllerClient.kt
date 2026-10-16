/* Odoo Client Library Kotlin adaptation. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyrights and full license are in META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/** Calls custom HTTP and JSON-RPC routes, optionally using an Odoo web session. */
class OdooControllerClient internal constructor(
    baseUrl: String,
    private val database: String?,
    private val login: String?,
    private val password: String?,
    private val transport: HttpTransport,
    private val contextState: OdooContext = OdooContext(),
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val authMutex = Mutex()
    private val requestIds = AtomicLong()
    @Volatile var userId: Int? = null
        private set

    /** Shared defaults and overrides, copied for the caller. */
    val context: Map<String, Any?> get() = contextState.merged()

    fun updateContext(values: Map<String, Any?>) { contextState.update(values) }

    private fun validateAuth(auth: String) {
        require(auth in setOf("none", "public", "user")) { "Auth must be none, public or user" }
    }

    init {
        val uri = URI(this.baseUrl)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Provide a valid HTTP(S) base URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Base URL must not include credentials, query or fragment" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port" }
        require(uri.path.split('/').none { it == "." || it == ".." } && '\\' !in uri.path) { "Invalid base URL path" }
    }

    /**
     * Logs in at /web/session/authenticate and caches the UID. The transport retains
     * the session cookie. Requires a web-login password, rather than an RPC API key.
     * Use force=true after session expiry; state-changing requests are never replayed.
     */
    suspend fun authenticate(force: Boolean = false): Int = authMutex.withLock {
        if (!force) userId?.let { return@withLock it }
        require(!database.isNullOrBlank() && !login.isNullOrBlank() && !password.isNullOrBlank()) {
            "Session authentication requires database, login and password"
        }
        userId = null
        val result = try {
            requestJsonRpc(url("/web/session/authenticate"), mapOf(
                "db" to database, "login" to login, "password" to password,
            ), emptyMap())
        } catch (error: RemoteException) {
            val data = (error.error as? Map<*, *>)?.get("data") as? Map<*, *>
            if (data?.get("name") == "odoo.exceptions.AccessDenied") throw AuthenticationException()
            throw error
        }
        val uid = ((result as? Map<*, *>)?.get("uid") as? Number)?.toInt()?.takeIf { it > 0 }
            ?: throw AuthenticationException()
        result["user_context"]?.let { contextState.setServer(stringMap(it)) }
        userId = uid
        uid
    }

    /**
     * Raw HTTP, preserving body and status. auth=user ensures a session; none/public
     * skip login and keep existing cookies. The server route enforces its own auth.
     * Context sets Accept-Language only; custom HTTP routes must handle other keys
     * explicitly in their own payload. Supply csrf_token when required by the route.
     */
    suspend fun http(
        path: String, method: String = "GET", body: String? = null,
        headers: Map<String, String> = emptyMap(), auth: String = "none",
        context: Map<String, Any?> = emptyMap(),
    ): HttpResponse {
        val target = url(path)
        validateAuth(auth)
        require(method in listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")) { "Unsupported HTTP method" }
        require(body == null || method !in listOf("GET", "HEAD")) { "GET and HEAD requests cannot have a body" }
        if (auth == "user") authenticate()
        val language = contextState.merged(context)["lang"] as? String
        val requestHeaders = if (language != null && headers.keys.none { it.equals("Accept-Language", true) }) {
            headers + ("Accept-Language" to language.replace('_', '-'))
        } else headers
        val response = transport.execute(HttpRequest(target, method, requestHeaders, body))
        if (response.statusCode == 401) userId = null
        return response
    }

    /** POSTs a JSON value verbatim, including top-level lists, to a type='http' route. */
    suspend fun postJson(
        path: String, body: Any?, auth: String = "none",
        headers: Map<String, String> = emptyMap(), context: Map<String, Any?> = emptyMap(),
    ): HttpResponse = http(path, "POST", JsonCodec.encode(body), jsonHeaders(headers), auth, context)

    /**
     * JSON-RPC with merged context in params.context. none/public do not log in;
     * user ensures a session. A custom route must consume context where its Odoo
     * version does not apply it automatically. No request is replayed on expiry.
     */
    suspend fun jsonRpc(
        path: String, params: Map<String, Any?> = emptyMap(),
        auth: String = "none", headers: Map<String, String> = emptyMap(),
        context: Map<String, Any?> = emptyMap(),
    ): Any? {
        val target = url(path)
        validateAuth(auth)
        val explicitContext = params["context"]?.let(::stringMap).orEmpty() + context
        // Validate caller data before authentication performs any network work.
        JsonCodec.encode(explicitContext)
        JsonCodec.encode(params)
        if (auth == "user") authenticate()
        val effectiveContext = contextState.merged(explicitContext)
        val parameters = if (effectiveContext.isNotEmpty() || "context" in params) {
            params + ("context" to effectiveContext)
        } else params
        return requestJsonRpc(target, parameters, headers)
    }

    @Deprecated("Use auth = \"user\" or auth = \"none\" instead")
    suspend fun http(
        path: String, method: String = "GET", body: String? = null,
        headers: Map<String, String> = emptyMap(), authenticated: Boolean,
    ): HttpResponse = http(path, method, body, headers, if (authenticated) "user" else "none")

    @Deprecated("Use auth = \"user\" or auth = \"none\" instead")
    suspend fun postJson(
        path: String, body: Any?, authenticated: Boolean, headers: Map<String, String> = emptyMap(),
    ): HttpResponse = postJson(path, body, if (authenticated) "user" else "none", headers)

    @Deprecated("Use auth = \"user\" or auth = \"none\" instead")
    suspend fun jsonRpc(
        path: String, params: Map<String, Any?> = emptyMap(),
        authenticated: Boolean, headers: Map<String, String> = emptyMap(),
    ): Any? = jsonRpc(path, params, if (authenticated) "user" else "none", headers)

    private suspend fun requestJsonRpc(
        target: String, params: Map<String, Any?>, headers: Map<String, String>,
    ): Any? {
        val id = requestIds.incrementAndGet()
        val response = transport.execute(HttpRequest(target, headers = jsonHeaders(headers), body = JsonCodec.encode(mapOf(
            "jsonrpc" to "2.0", "method" to "call", "id" to id, "params" to params,
        ))))
        if (response.statusCode == 401) {
            userId = null
            throw AuthenticationException()
        }
        if (response.statusCode !in 200..299) throw HttpException(response.statusCode)
        val envelope = stringMap(JsonCodec.decode(response.body))
        val fault = envelope["error"] as? Map<*, *>
        val code = (fault?.get("code") as? Number)?.toInt()
        val data = fault?.get("data") as? Map<*, *>
        val sessionExpired = code == 100 || data?.get("name") == "odoo.http.SessionExpiredException"
        // Odoo authenticates before dispatch; a session-expired fault can have id=null.
        val earlySessionFault = envelope["id"] == null && sessionExpired
        if (envelope["jsonrpc"] != "2.0" ||
            (!earlySessionFault && (envelope["id"] as? Number)?.toLong() != id)) {
            throw ProtocolException("Invalid JSON-RPC version or request ID")
        }
        envelope["error"]?.let { error ->
            if (sessionExpired) {
                userId = null
                throw AuthenticationException("Odoo web session expired")
            }
            throw RemoteException(code, error)
        }
        if (!envelope.containsKey("result")) throw ProtocolException("JSON-RPC result is missing")
        return envelope["result"]
    }

    private fun jsonHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals("Content-Type", ignoreCase = true) } +
            ("Content-Type" to "application/json; charset=utf-8")

    private fun url(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//")) { "Controller path must start with a single /" }
        val uri = URI(path)
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null) { "Provide a controller path on the configured server" }
        require(uri.path.split('/').none { it == "." || it == ".." } && '\\' !in uri.path) { "Invalid controller path" }
        // Concatenation preserves a deployment prefix and cannot change the origin.
        return baseUrl + path
    }
}
