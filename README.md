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
Android app project with its own Gradle wrapper. Requirements: JDK 17, Android SDK 36,
Build Tools 35.0.0, and Android 8.0+ to run the example.

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
Enter your Odoo URL, database, username, and password or RPC API key,
then tap **Connect and view contacts**.

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
val client = OdooClient.rpc(
    baseUrl = "https://odoo.example.com",
    database = "my_database",
    login = "user@example.com",
    password = passwordFromUser,
)
val contacts = client.getModel("res.partner").searchRead(
    fields = listOf("name", "email"),
    limit = 10,
)
```

Supports XML-RPC, JSON-RPC, JSON-2, coroutine requests, CRUD helpers,
and UTC date utilities. See the [library documentation](docs/README.md),
[API reference](docs/api.md), and [usage examples](docs/examples.md).

## Odoo references and attribution

- [Official Odoo Python client library](https://github.com/odoo/odoo-client-lib): original library used as the basis of this adaptation.
- [Odoo server](https://github.com/odoo/odoo): server and protocol reference.
- [Original license](https://github.com/odoo/odoo-client-lib/blob/master/licence.txt).
- [Credits and redistribution](docs/attribution.md).

The AAR and source JAR include Odoo's original license and copyright notices.
Preserve them when redistributing. Repository and local Maven instructions
are in the [publishing guide](docs/publishing.md).
