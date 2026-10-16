# Installation and first request

## Requirements

- Android 8.0+ (`minSdk = 26`).
- Android Studio's bundled JDK (17, 21, or 25), using the default Gradle JDK selection.
- Android SDK 36 and Build Tools 35.0.0.
- Gradle 9.1.0, AGP 8.13.2, and Kotlin 2.3.21, specified in the project configuration.

The project uses the selected JDK and targets Java 17 bytecode for both Java and
Kotlin. It does not require a separate JDK 17 installation or a project-specific
`JAVA_HOME`. For terminal builds, your environment's `JAVA_HOME` or `java` on
`PATH` must select a compatible JDK, such as 17, 21, or 25.

Your app's Kotlin compiler must support the library's Kotlin metadata.
The example project includes the versions used to build the AAR.

## Import the AAR

From the repository root:

```sh
./gradlew assembleRelease
```

Copy `build/outputs/aar/odoo-client-release.aar` into your app at
`app/libs/odoo-client.aar`, then add:

```kotlin
dependencies {
    implementation(files("libs/odoo-client.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

A local AAR requires explicit declarations for its external dependencies.
Keep `google()` and `mavenCentral()` in your repository configuration.
The library manifest supplies the `INTERNET` permission.

The [standalone example](../example/README.md) already includes
the AAR and this configuration. Open its root directory in Android Studio,
select `app`, and click Run. It requests credentials and displays contacts;
see its README for local server and emulator settings.

## First JSON-RPC request

```kotlin
import io.github.elierwclik.odooclient.OdooClient

suspend fun fetchContacts(passwordFromUser: String): List<Map<String, Any?>> {
    val client = OdooClient.rpc(
        baseUrl = "https://odoo.example.com",
        database = "my_database",
        login = "user@example.com",
        password = passwordFromUser,
    )
    return client.getModel("res.partner").searchRead(
        fields = listOf("name", "email"),
        limit = 10,
    )
}
```

Call this function inside a coroutine owned by your Activity, ViewModel, or
repository. Network operations use `Dispatchers.IO`. Reuse the client instance
when you want to retain authentication and cookies.

For XML-RPC, add `protocol = OdooProtocol.XML_RPC` and import
`io.github.elierwclik.odooclient.OdooProtocol`. HTTP or HTTPS is selected by
`baseUrl`. Provide the server's base URL without `/jsonrpc` or `/xmlrpc`.

## JSON-2

```kotlin
val client = OdooClient.json2(
    baseUrl = "https://odoo.example.com",
    apiKey = apiKeyFromUser,
    database = "my_database",
)
val contacts = client.getModel("res.partner").searchRead(fields = listOf("name"))
```

JSON-2 uses API keys and named parameters according to the
[official Odoo documentation](https://www.odoo.com/documentation/19.0/developer/reference/external_api.html).
Omit `database` when the host identifies the database.
The user associated with the API key must have access to the requested models.

## Attribution

Keep [LICENSE](../LICENSE) and [NOTICE.txt](../NOTICE.txt) in your app's
legal notices. The AAR includes copies in its `classes.jar`, and the example
app includes them in its assets. Original Odoo attribution is retained even
though this repository distributes only the Kotlin adaptation.
