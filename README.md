# Odoo Client Library for Android · Kotlin

Android/Kotlin adaptation maintained by [elierwclik](https://github.com/elierwclik)
in [elierwclik/odoo-client-lib](https://github.com/elierwclik/odoo-client-lib).
Based on Odoo's official Python client library,
[odoo/odoo-client-lib](https://github.com/odoo/odoo-client-lib).

**Odoo s.a., OpenERP s.a., and the original authors retain their copyrights
in the original code.** The original BSD 3-Clause [license](LICENSE) and
[attribution](NOTICE.txt) are preserved. This is an independent adaptation;
Python source files are not included.

## Contents

```text
build.gradle.kts     Android library build configuration
settings.gradle.kts  Library project settings
src/                 Kotlin library and tests
docs/                Library documentation
example/             Simple Android app importing the compiled AAR
```

The repository root is the Android library project. Open this directory in
Android Studio to work on the library. The `example/` directory is an independent
Android app project with its own Gradle wrapper. Requirements: Android Studio's bundled
JDK (17, 21, or 25), Android SDK 36,
Build Tools 35.0.0, and Android 8.0+ to run the example.

Keep Android Studio's default Gradle JDK selection. Both projects use the selected
JDK and compile Java and Kotlin to Java 17 bytecode; no separate JDK 17 installation
or project-specific `JAVA_HOME` is required. Terminal builds use your environment's
`JAVA_HOME`, or `java` on `PATH`, which must select a Gradle 9.1.0-compatible JDK
such as 17, 21, or 25.

## Build the library

From the repository root:

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
```

The output is `build/outputs/aar/odoo-client-release.aar`.
Build output, caches, local SDK settings, and all `distribution/` directories
are ignored by Git.

## Run the example

Open **`example/`** in Android Studio, select **app**, and click **Run**.
Enter your Odoo URL, database, username, and password (or select the API key
option), then tap **Connect and view contacts**. Accounts with TOTP enabled
prompt for the authenticator code.

The example imports **`example/app/libs/odoo-client.aar`**. This file is included
so the app can be built independently of the library source project.
From `example/`, you can also build it with:

```sh
./gradlew :app:assembleDebug
```

[Example instructions](example/README.md) explain emulator URLs and installation.

## First request

```kotlin
import io.github.elierwclik.odooclient.OdooClient

// Run inside a coroutine and obtain credentials from the user.
val client = OdooClient.authenticate(
    baseUrl = "https://odoo.example.com",
    database = "my_database",
    login = "user@example.com",
    password = passwordFromUser,
    onTwoFactorCode = { requestCodeFromUser() }, // Suspend while the app asks for a TOTP code.
)
val contacts = client.getModel("res.partner").searchRead(
    fields = listOf("name", "email"),
    limit = 10,
)
```

`authenticate()` uses the same web session for models and controllers, including
accounts with authenticator/TOTP enabled. The callback is skipped for accounts
without 2FA. Use `rpc()` or `json2()` for API keys; see [2FA details](docs/api.md#two-factor-authentication-totp).

Supports XML-RPC, JSON-RPC, JSON-2, custom HTTP/JSON-RPC controllers with optional
web-session authentication, coroutine requests, CRUD helpers,
and UTC date utilities. See the [library documentation](docs/README.md),
[API reference](docs/api.md), and [usage examples](docs/examples.md).

## Call a custom controller

```kotlin
val controllers = client.getControllers()
val response = controllers.postJson(
    path = "/stock_entrego_app/signatures",
    body = listOf(mapOf("id" to 123, "signure" to signatureBase64)),
    auth = "user",
)
val result = response.json() as Map<*, *>
// HTTP 200 means saved; HTTP 202 means accepted for background processing.
```

Controllers accept `auth = "none"` (default), `"public"`, or `"user"`.
Use `client.updateContext(mapOf("lang" to "es_ES", "allowed_company_ids" to companyIds))`
for shared model and JSON-RPC controller context; per-call `context` overrides it.
Raw HTTP bodies keep their original format; see [context handling](docs/api.md#context).

For public routes, use `OdooClient.controllers(baseUrl)` without credentials.
Use `http()` for raw HTTP, `postJson()` for a JSON body, and `jsonRpc()` for
a custom Odoo JSON-RPC route. See [controller examples](docs/examples.md#custom-http-and-json-rpc-controllers).

## Odoo references and attribution

- [Official Odoo Python client library](https://github.com/odoo/odoo-client-lib): original library used as the basis of this adaptation.
- [Odoo server](https://github.com/odoo/odoo): server and protocol reference.
- [Original license](https://github.com/odoo/odoo-client-lib/blob/master/licence.txt).
- [Credits and redistribution](docs/attribution.md).

The AAR and source JAR include Odoo's original license and copyright notices.
Preserve them when redistributing. Repository and local Maven instructions
are in the [publishing guide](docs/publishing.md).
