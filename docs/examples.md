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
val context = client.getUserContext() + mapOf("lang" to "en_US")
val countries = client.getModel("res.country").searchRead(
    fields = listOf("name"),
    context = context,
)
val metadata = partners.fieldsGet(attributes = listOf("type", "string", "required"))
```

The selected language must be installed in the database.

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
    authenticated = true,
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
    authenticated = true,
)
```

The `/my/...` routes above are examples; implement them on your Odoo server.
`stock_entrego_app` keeps its HTTP controller and does not require conversion to
JSON-RPC. Do not use `jsonRpc()` for that HTTP endpoint.

An existing XML/JSON-RPC client can call `client.getControllers()` to reuse its
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
                authenticated = true,
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
