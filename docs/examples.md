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
