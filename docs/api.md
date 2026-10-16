# Kotlin API reference

Import public classes from `io.github.elierwclik.odooclient`.
Network methods are `suspend`. See [examples.md](examples.md) for usage.

## OdooClient

| Method | Behavior |
| --- | --- |
| `authenticate(baseUrl, database, login, password, protocol, transport, context, onTwoFactorCode = null)` | Signs in immediately with a web-login password; returns a shared web-session client for models and controllers, with optional TOTP verification. |
| `rpc(baseUrl, database, login, password, protocol, userId, transport, context)` | RPC client; `protocol` defaults to `JSON_RPC`. Optional `userId` skips the first login when already known. |
| `json2(baseUrl, apiKey, database, transport, context)` | JSON-2 client authenticated with an API key; `database` is optional. |
| `controllers(baseUrl, database = null, login = null, password = null, transport, context)` | Custom-route client; no credentials needed for public routes. |
| `getConnection(hostname, database, login, password, protocol, port, userId, transport, context)` | Migration shortcut; default port 8069 and protocol `xmlrpc`. |
| `getModel(name)` | Cached model proxy; no network request. |
| `getService(name)` | RPC service proxy such as `common`, `object`, or `db`; unavailable for JSON-2. |
| `checkLogin(force = true): Int` | Validates credentials and returns the UID; throws on failure. |
| `getUserContext(force = false): Map<String, Any?>` | Fetches and caches user context; `force` refreshes it. |
| `context` | Snapshot of user defaults merged with shared app overrides. |
| `updateContext(values)` | Merge app overrides for future model and controller calls. |
| `getControllers(): OdooControllerClient` | Cached custom-route client sharing this RPC client's transport and credentials. |

`baseUrl` supports a path prefix, but must not contain embedded credentials,
query parameters, or a fragment. Include a port if required.
`getConnection()` accepts `xmlrpc`, `xmlrpcs`, `jsonrpc`, `jsonrpcs`, `json2`, and
`json2s`; the `s` suffix selects HTTPS.

The `userId` property holds the authenticated UID for RPC. For JSON-2 it remains
`null`; `checkLogin()` retrieves the UID through `res.users/context_get`.
Credentials are immutable: create a new client to change them.

Create one client with `OdooClient.authenticate(...)` per account/server and reuse
it throughout the app. It logs in through `/web/session/authenticate`, retains the
cookie in the shared transport, and uses `/web/dataset/call_kw` for model calls
without sending the password again. This supports accounts with two-factor
authentication, which require API keys for traditional external RPC. It requires a web-login password; use `rpc()` or `json2()` for API
keys. No extra `checkLogin()` is necessary. Sessions are kept in memory by the
default transport; this factory does not restore sessions from disk.
For session clients, `checkLogin(force = true)` validates the current cookie with
`/web/session/get_session_info` rather than submitting the password again.
`rpc()` and `json2()` retain their existing API authentication behavior.

## Two-factor authentication (TOTP)

Supply `onTwoFactorCode = { requestCodeFromUser() }` to `OdooClient.authenticate()`.
It is a suspending callback and is invoked only when Odoo presents the standard
`auth_totp` authenticator challenge. Password-only accounts never invoke it.
The library retains the partial session cookie, reads Odoo's CSRF token, submits
the six-digit code to `/web/login/totp`, and verifies the final session before
returning a client. Codes are not cached, logged, or used to generate API keys.
The callback is not retained for future logins.

Without a callback, a supported pending challenge raises
`TwoFactorRequiredException`. Standalone controller clients can continue on the
same instance with `completeTwoFactor(code)` and inspect `twoFactorRequired`.
`InvalidTwoFactorCodeException` leaves the controller challenge available for an
explicit retry; malformed codes fail locally. Cancellation and HTTP rate-limit
responses are propagated; neither codes nor business requests are retried automatically.
A CSRF/session failure may require a fresh `authenticate(force = true)`.

This supports the standard Odoo authenticator/TOTP form, inspected against Odoo
18 and 19. SMS codes, email codes, passkeys, SSO, and customized MFA forms are not
implemented by this flow. The library does not register a trusted device; an
existing trusted-device cookie supplied by a custom transport may be honored.

## Context

All client factories accept `context = mapOf(...)`. `authenticate()` captures the
server's `user_context` from web login. For clients created with `rpc()` or
`json2()`, call `getUserContext()` to load user defaults when needed.
`getUserContext(force = true)` refreshes server defaults without discarding app
overrides. `client.context` is a detached snapshot; `client.updateContext(...)`
and `client.getControllers().updateContext(...)` change the same shared defaults.

Precedence is server defaults < app overrides < per-call context. Nested values
such as `allowed_company_ids` are replaced as a whole. Per-call overrides do not
change shared state. Model calls send the merged context in web-session/RPC kwargs or JSON-2
parameters, including generic `model.call(..., kwargs = mapOf("context" to ...))`.
Typical keys include `lang`, `tz`, `allowed_company_ids`, and `active_test`.
Company IDs select companies already permitted by Odoo; they do not grant access.

JSON-RPC controllers receive `params.context`; the explicit `context` argument
wins over `params["context"]`. Custom controllers must accept and apply context
if their Odoo dispatcher does not do so. Verify this for your server version.
Raw `http()` and `postJson()` set `Accept-Language` from `lang`, unless the caller
supplies that header. They preserve the body: other context keys must be carried
in the custom route's own payload and applied by that route. Do not assume
sending a header or arbitrary JSON changes `request.env.context` automatically.

## OdooControllerClient

| Method | Result |
| --- | --- |
| `authenticate(force = false, onTwoFactorCode = null)` | Web-session UID, cached unless forced; optional suspending TOTP callback. |
| `completeTwoFactor(code)` | Complete a pending TOTP challenge on this same client. |
| `twoFactorRequired` | Whether this client has a pending TOTP form. |
| `checkSession()` | Verify the current session cookie without repeating password login. |
| `http(path, method = "GET", body = null, headers = {}, auth = "none", context = {})` | Raw `HttpResponse`; body is a string sent verbatim. |
| `postJson(path, body, auth = "none", headers = {}, context = {})` | Raw `HttpResponse`; serializes a JSON value, including a top-level list. |
| `jsonRpc(path, params = {}, auth = "none", headers = {}, context = {})` | Unwrapped JSON-RPC result (`Any?`). |

Paths start with `/` and refer to the configured server. A base URL path prefix
is preserved; query strings are allowed. Absolute URLs, network-path references,
fragments, and path traversal are rejected before authentication. Methods never
send credentials to another origin. `postJson()` and `jsonRpc()` set JSON content
type; `http()` uses the headers/body supplied by the caller.

`auth` accepts Odoo's route names: `"none"` (default), `"public"`, and `"user"`.
`"none"` and `"public"` skip automatic login and retain existing cookies. Odoo's
server route decides whether to use no user, the public user, or the session user;
this argument cannot change the route's declared authentication or permissions.
`"user"` authenticates lazily through `/web/session/authenticate`
and reuses the session cookie. It requires database, login, and a web-login
password. RPC/API-key login is separate and does not establish a web session.
`getControllers()` is for XML/JSON-RPC clients; for a JSON-2 client, create a
separate controller client with web-login credentials.

The old `authenticated` Boolean overloads remain available but are deprecated.

`auth = "none"` skips automatic login. It does not clear cookies already
stored by the transport; use a separate client/transport for an anonymous session.
Custom auth (such as a bearer header) can be supplied in `headers` without session
authentication. A custom transport must retain session cookies for authenticated
routes, as the default `UrlConnectionTransport` does.

Raw HTTP calls return all status codes for the caller to handle, including 202,
302, 403, and 503. They never follow redirects. Call `response.json()` to decode a
JSON body as maps/lists/scalars; it does not check the HTTP status. Non-JSON bodies
raise `ProtocolException` on decoding. HTTP 401 clears the cached session UID.

`jsonRpc()` sends the JSON-RPC envelope to the specified controller path, with
named `params` and a unique request ID. This works with Odoo 17/18 `type='json'`
and newer `type='jsonrpc'` routes. It validates the response ID, unwraps `result`,
and throws `RemoteException` for faults or `HttpException` for unsuccessful HTTP
statuses. HTTP 401 or an Odoo session-expired fault clears the cached UID and
throws `AuthenticationException`.

No call is replayed automatically. After a login redirect or session expiration,
authenticate with `force = true` and let the application decide whether to resend
a write. CSRF requirements remain those of the server route: callers must supply
tokens for HTTP routes that require them. The library does not disable CSRF.

## OdooModel

`OdooRecord` is `Map<String, Any?>`; `OdooDomain` is `List<Any?>`.

| Method | Result |
| --- | --- |
| `search(domain = [], offset = 0, limit = null, order = null, context = {})` | `List<Int>` of matching IDs. |
| `searchCount(domain = [], context = {})` | `Int` count, including zero. |
| `read(ids: List<Int>, fields = [], context = {})` | `List<OdooRecord>` in requested ID order. |
| `read(id: Int, fields = [], context = {})` | `OdooRecord?`; `null` when the ID is absent from the response. |
| `searchRead(domain = [], fields = [], offset = 0, limit = null, order = null, context = {})` | `List<OdooRecord>` from one `search_read` call. |
| `create(values, context = {})` | `Int` ID of the new record. |
| `createMany(values: List<OdooRecord>, context = {})` | `List<Int>` of new IDs; requires server support for multiple creation. |
| `write(ids, values, context = {})` | `Boolean` returned by the server. |
| `unlink(ids, context = {})` | `Boolean` returned by the server. |
| `fieldsGet(fields = [], attributes = [], context = {})` | `Map<String, Any?>` containing field metadata. |
| `call(method, args = [], kwargs = {})` | `Any?`; calls a public server method. |

In the table, `[]` and `{}` denote empty Kotlin lists and maps.
IDs must be positive, `offset` non-negative, and a specified `limit` positive.
An empty field list requests all fields.

`read()` omits missing IDs and preserves duplicate IDs in the requested order.
Permission errors propagate; they do not mean that a record is absent.
`read([])` and `createMany([])` return empty lists without a request.
`write([])` and `unlink([])` return `true` without a request.

For RPC, `call()` sends `execute_kw` with positional and named arguments.
For JSON-2, use `kwargs` to avoid introspection. With positional `args`, the client
fetches `/doc-bearer/<model>.json` once per model and converts arguments using
server metadata. Duplicate or excess arguments are rejected.
Standard CRUD helpers use named parameters directly.

`browse()` is rejected because its server-side proxy is not a serializable remote
result. Each request has its own server transaction. Use one server method when
several actions must be atomic.

## RPC services

```kotlin
val version = client.getService("common").call("version")
```

`OdooService.call(method, vararg args): Any?` does not add authentication arguments
automatically. Available services and methods depend on the server.
Model calls handle authentication automatically.

## Transport

`HttpTransport.execute(request: HttpRequest): HttpResponse` is injectable.
`HttpRequest` exposes `url`, `method`, `headers`, and `body`.
`HttpResponse` exposes `statusCode`, `body`, and `json(): Any?`.

`UrlConnectionTransport` defaults:

- Connection timeout: 15,000 ms.
- Read timeout: 60,000 ms.
- Maximum response size: 16 MiB.

It uses system TLS validation, per-instance cookies, and `Dispatchers.IO`.
Redirects are disabled, and writes are not retried automatically.
Cancellation interrupts the worker thread, but a socket may take until its timeout
to terminate. A custom transport must manage its own background execution and
respect cancellation.

## Dates

| `OdooDates` method | Types |
| --- | --- |
| `strToDate(String?)` / `dateToStr(LocalDate?)` | `LocalDate?` / `String?` |
| `strToTime(String?)` / `timeToStr(LocalTime?)` | `LocalTime?` / `String?` |
| `strToDatetime(String?)` / `datetimeToStr(LocalDateTime?)` | `LocalDateTime?` / `String?` |
| `toInstant(String)` / `fromInstant(Instant)` | `Instant` / `String` |

Formats are `yyyy-MM-dd`, `HH:mm:ss`, and `yyyy-MM-dd HH:mm:ss`.
Server datetime strings represent UTC. Fractional seconds are discarded when
parsing time/datetime, matching the original library's behavior.
Empty or `null` inputs return `null`; invalid dates throw.
Send `false` in your values map to clear an Odoo field.

## Errors

| Exception | Meaning |
| --- | --- |
| `AuthenticationException` | Login failure or HTTP 401. |
| `TwoFactorRequiredException` | A supported TOTP challenge is waiting for a code. |
| `InvalidTwoFactorCodeException` | Odoo rejected the code; the pending controller login can be retried explicitly. |
| `RemoteException` | RPC fault or structured JSON-2 error; exposes `code` and `error`. |
| `HttpException` | Failed HTTP status without a structured JSON-2 error; exposes `statusCode`. |
| `ProtocolException` | Invalid response, unexpected type, or oversized response. |
| `IOException` | Connection, timeout, or TLS failure. |
| `IllegalArgumentException` | Invalid local arguments or an unsupported value type. |

Odoo authentication, remote, HTTP, and protocol errors extend `OdooException`,
which extends `IOException`. Both 2FA exceptions extend `AuthenticationException`;
catch them before the general authentication error when prompting or retrying.
Cancellation retains `CancellationException`. Avoid logging
`RemoteException.error` indiscriminately; it can contain server information.
