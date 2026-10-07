# Kotlin API reference

Import public classes from `io.github.elierwclik.odooclient`.
Network methods are `suspend`. See [examples.md](examples.md) for usage.

## OdooClient

| Method | Behavior |
| --- | --- |
| `rpc(baseUrl, database, login, password, protocol, userId, transport)` | RPC client; `protocol` defaults to `JSON_RPC`. Optional `userId` skips the first login when already known. |
| `json2(baseUrl, apiKey, database, transport)` | JSON-2 client authenticated with an API key; `database` is optional. |
| `getConnection(hostname, database, login, password, protocol, port, userId, transport)` | Migration shortcut; default port 8069 and protocol `xmlrpc`. |
| `getModel(name)` | Cached model proxy; no network request. |
| `getService(name)` | RPC service proxy such as `common`, `object`, or `db`; unavailable for JSON-2. |
| `checkLogin(force = true): Int` | Validates credentials and returns the UID; throws on failure. |
| `getUserContext(force = false): Map<String, Any?>` | Fetches and caches user context; `force` refreshes it. |

`baseUrl` supports a path prefix, but must not contain embedded credentials,
query parameters, or a fragment. Include a port if required.
`getConnection()` accepts `xmlrpc`, `xmlrpcs`, `jsonrpc`, `jsonrpcs`, `json2`, and
`json2s`; the `s` suffix selects HTTPS.

The `userId` property holds the authenticated UID for RPC. For JSON-2 it remains
`null`; `checkLogin()` retrieves the UID through `res.users/context_get`.
Credentials are immutable: create a new client to change them.

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
`HttpResponse` exposes `statusCode` and `body`.

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
| `RemoteException` | RPC fault or structured JSON-2 error; exposes `code` and `error`. |
| `HttpException` | Failed HTTP status without a structured JSON-2 error; exposes `statusCode`. |
| `ProtocolException` | Invalid response, unexpected type, or oversized response. |
| `IOException` | Connection, timeout, or TLS failure. |
| `IllegalArgumentException` | Invalid local arguments or an unsupported value type. |

The first four extend `OdooException`, which extends `IOException`.
Cancellation retains `CancellationException`. Avoid logging
`RemoteException.error` indiscriminately; it can contain server information.
