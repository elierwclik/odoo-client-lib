# Usage examples

These snippets use a configured `OdooClient` and run inside a coroutine.
Creation, update, and deletion calls modify real server records when executed.
Choose the records appropriate for your application.

## Search and read

```kotlin
val partners = client.getModel("res.partner")
val rows = partners.searchRead(
    domain = listOf(listOf("is_company", "=", true), listOf("name", "ilike", "Acme")),
    fields = listOf("name", "email"),
    offset = 0,
    limit = 20,
    order = "name asc",
)
for (row in rows) {
    val id = row["id"] as Number
    val name = row["name"] as String
    val email = row["email"] as? String // An empty field may contain false.
}
```

Domains use lists of triples. Prefix logical operators are strings:

```kotlin
val domain = listOf(
    "|",
    listOf("name", "ilike", "Acme"),
    listOf("email", "ilike", "@example.com"),
)
val ids = partners.search(domain)
val records = partners.read(ids, fields = listOf("name"))
val record = partners.read(12, fields = listOf("name"))
```

Odoo validates operators and available fields.

## Create, update, and delete

```kotlin
val newId = partners.create(mapOf("name" to "Example contact"))
partners.write(listOf(newId), mapOf("email" to "contact@example.com"))
partners.write(listOf(newId), mapOf("email" to false)) // Clear the field.
partners.unlink(listOf(newId))
```

Use a server method if these actions must share one transaction.

## Context and metadata

```kotlin
// authenticate() already loads the web session's user context.
// For rpc()/json2() clients, load defaults once with client.getUserContext().
client.updateContext(mapOf(
    "lang" to "es_ES",
    "tz" to "America/Toronto",
    "allowed_company_ids" to selectedCompanyIds, // Companies permitted for this user.
))
val countries = client.getModel("res.country").searchRead(
    fields = listOf("name"),
    context = mapOf("lang" to "en_US"), // Override only for this call.
)
val result = client.getControllers().jsonRpc(
    path = "/my/private/json_route",
    auth = "user",
    context = mapOf("active_test" to false),
)
val metadata = partners.fieldsGet(attributes = listOf("type", "string", "required"))
```

The selected language must be installed in the database. `allowed_company_ids`
selects companies already permitted by the server. Model calls merge server
user defaults, app overrides, and per-call context, in that order. Controllers
receive JSON-RPC context in `params.context`; custom routes must accept/apply it
where their Odoo version does not do so automatically. For raw HTTP JSON routes,
include other context keys in the payload format that your server supports;
`postJson()` preserves the original body (including top-level arrays).

Controller authentication modes:

```kotlin
val routes = client.getControllers()
routes.jsonRpc("/my/no_auth_route") // auth = "none" by default; no automatic login.
routes.jsonRpc("/my/public_route", auth = "public") // Existing session or Odoo's public user.
routes.jsonRpc("/my/private/json_route", auth = "user") // Ensure a web session.
```

These flags describe the expected server route. Odoo enforces the route's own
`auth`; a client cannot change it. `none` and `public` retain existing cookies.

## Relationships and attachments

```kotlin
// many2one: send the related record's ID.
partners.write(listOf(partnerId), mapOf("country_id" to countryId))

// many2many: replace relationships using an Odoo command.
partners.write(listOf(partnerId), mapOf("category_id" to listOf(listOf(6, 0, categoryIds))))

// JSON binary fields use Base64 strings.
val encoded = java.util.Base64.getEncoder().encodeToString(fileBytes)
val attachmentId = client.getModel("ir.attachment").create(
    mapOf("name" to "document.pdf", "type" to "binary", "datas" to encoded),
)
```

Send strings, numbers, booleans, `null`, lists, or maps with `String` keys.
JSON also supports object arrays and `IntArray`/`LongArray`.
XML-RPC additionally supports `ByteArray`. Convert app entities and `java.time`
objects before sending them.

## UTC dates

```kotlin
import io.github.elierwclik.odooclient.OdooDates
import java.time.Instant

val text = OdooDates.fromInstant(Instant.now())
val instant = OdooDates.toInstant("2026-10-07 14:30:15")
val date = OdooDates.strToDate("2026-10-07")
```

`toInstant()` interprets UTC. `strToDatetime()` returns a datetime without a zone,
representing UTC in Odoo's server format.

## Custom JSON-2 method

```kotlin
val result = client.getModel("custom.model").call(
    method = "public_method",
    kwargs = mapOf("ids" to listOf(12), "parameter" to "value"),
)
```

For a model method, omit `ids` when its contract requires it.
Parameter names must match the server method's signature.

## Errors and timeouts

```kotlin
import io.github.elierwclik.odooclient.OdooClient
import io.github.elierwclik.odooclient.AuthenticationException
import io.github.elierwclik.odooclient.OdooException
import io.github.elierwclik.odooclient.UrlConnectionTransport
import java.io.IOException

val transport = UrlConnectionTransport(connectTimeoutMillis = 10_000, readTimeoutMillis = 30_000)
val client = OdooClient.rpc(serverUrl, database, login, password, transport = transport)

try {
    val rows = client.getModel("res.partner").searchRead(fields = listOf("name"), limit = 10)
} catch (error: AuthenticationException) {
    // Request valid credentials.
} catch (error: OdooException) {
    // Present an appropriate message for permissions, data, or protocol errors.
} catch (error: IOException) {
    // Offer to retry when the network becomes available.
}
```

Do not catch coroutine cancellation as a business error.

## Custom HTTP and JSON-RPC controllers

Use a standalone client for public endpoints. No login is attempted:

```kotlin
val publicRoutes = OdooClient.controllers("https://odoo.example.com")
val page = publicRoutes.http("/my/public/page")
val info = publicRoutes.jsonRpc("/my/public/info", params = mapOf("code" to "ABC"))
```

For `auth='user'` controllers, configure web-login credentials and request
authentication explicitly:

```kotlin
val routes = OdooClient.controllers(
    baseUrl = "http://10.0.1.152:8069",
    database = databaseFromUser,
    login = loginFromUser,
    password = passwordFromUser,
)

// Odoo's type='http': sends this list as the raw JSON body.
val response = routes.postJson(
    path = "/stock_entrego_app/signatures",
    body = listOf(
        mapOf("id" to 123, "signure" to firstSignatureBase64),
        mapOf("id" to 124, "signure" to secondSignatureBase64),
    ),
    auth = "user",
)
when (response.statusCode) {
    200 -> println("Signatures saved")
    202 -> println("Batch accepted: ${(response.json() as Map<*, *>)["batch_id"]}")
    302, 303, 401 -> {
        // Reauthenticate before the application decides whether to resend.
        routes.authenticate(force = true)
    }
    503 -> println("Server busy; offer to retry later")
    else -> println("HTTP error ${response.statusCode}")
}

// Odoo's type='json' in 17/18, or type='jsonrpc' in newer releases:
val result = routes.jsonRpc(
    path = "/my/private/json_route",
    params = mapOf("pickings" to listOf(123, 124)),
    auth = "user",
)
```

The `/my/...` routes above are examples; implement them on your Odoo server.
`stock_entrego_app` keeps its HTTP controller and does not require conversion to
JSON-RPC. Do not use `jsonRpc()` for that HTTP endpoint.

For an app using both models and controllers, authenticate once and share the
returned client (inside a coroutine):

```kotlin
val client = OdooClient.authenticate(
    baseUrl = "https://odoo.example.com",
    database = databaseFromUser,
    login = loginFromUser,
    password = passwordFromUser,
)
val contacts = client.getModel("res.partner").searchRead(
    fields = listOf("name", "email"),
    limit = 10,
)
val result = client.getControllers().jsonRpc(
    path = "/my/private/json_route",
    params = mapOf("partner_id" to 10),
    auth = "user",
)
```

This uses the login UID for model RPC and the existing cookie for controllers.
Reuse this client per account/server; creating another client starts another login.
Session expiration handling remains explicit, as described above.

An existing XML/JSON-RPC client created with `rpc()` can call `client.getControllers()` to reuse its
transport and configured credentials. This still performs a separate web-session
login; `checkLogin()` alone does not create a session cookie. Web-login passwords
are required for these session routes, even if an API key works for model RPC.

For custom token authentication, set headers and skip automatic session login:

```kotlin
val response = publicRoutes.http(
    path = "/my/token/route",
    headers = mapOf("Authorization" to "Bearer $tokenFromUser"),
)
```

Call these suspending methods from your ViewModel or a Compose coroutine scope.
For example, `routes` can be a remembered/injected instance configured above:

```kotlin
val scope = rememberCoroutineScope()
var sending by remember { mutableStateOf(false) }
var message by remember { mutableStateOf("") }

Button(enabled = !sending, onClick = {
    sending = true
    scope.launch {
        try {
            val response = routes.postJson(
                "/stock_entrego_app/signatures", signatures,
                auth = "user",
            )
            message = when (response.statusCode) {
                200 -> "Firmas guardadas"
                202 -> "Lote recibido y en procesamiento"
                else -> "Error HTTP ${response.statusCode}"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            message = "No se pudieron enviar las firmas"
        } finally {
            sending = false
        }
    }
}) { Text(if (sending) "Enviando..." else "Enviar firmas") }
Text(message)
```

The Compose snippet uses `androidx.compose.runtime.*`, Material 3 `Button`/`Text`,
`kotlinx.coroutines.launch`/`CancellationException`, and `java.io.IOException`.
`signatures` is a list of maps in the format shown above. No additional HTTP
library is needed. A Compose scope is cancelled when its composable leaves the
composition; use a ViewModel scope when a submission should survive navigation.

### Local Odoo at 10.0.1.152:8069

Use `http://10.0.1.152:8069` as the base URL and
`/stock_entrego_app/signatures` as the path. The device must be able to reach
that LAN address. For local HTTP testing, allow cleartext in your app's debug
manifest (`app/src/debug/AndroidManifest.xml`):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>
```

If the app already defines a network security configuration, allow this local
host in its debug configuration instead. HTTPS URLs do not require this setting.
The server route stays `type='http', auth='user', methods=['POST'], csrf=False`;
the library logs in via `/web/session/authenticate` and sends the cookie with
the raw JSON array. `signure` holds an image encoded as base64 without line breaks.
