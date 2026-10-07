# Standalone Android example importing odoo-client

Repository: [elierwclik/odoo-client-lib](https://github.com/elierwclik/odoo-client-lib).
Maintainer: [elierwclik](https://github.com/elierwclik).
Official original Python library: [odoo/odoo-client-lib](https://github.com/odoo/odoo-client-lib).
Server reference: [odoo/odoo](https://github.com/odoo/odoo).
The full library guide is at [library documentation](../docs/README.md) in the complete repository.

This is a complete Android project with one `app` module. It imports the
compiled library from **`app/libs/odoo-client.aar`**. You can move this directory
to any location; it includes its own Gradle Wrapper.

## Run the example

1. In Android Studio, choose **File → Open** and select this
   **`example/`** directory containing `settings.gradle.kts`.
2. Use **JDK 17** for Gradle and install Android SDK 36 if requested.
3. Wait for synchronization, select **app** and an Android 8.0+ device/emulator,
   then click **▶ Run**.
4. Enter your Odoo URL, database, username, and password or RPC API key.
5. Tap **Connect and view contacts** to fetch up to ten contacts.
   The example does not create or update records.

If Odoo runs on your computer and you use the Android emulator, your development
URL can be `http://10.0.2.2:8069`. Use HTTPS for a remote server.
HTTP is enabled only in the debug variant.

From a terminal in this directory:

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

`installDebug` requires a connected device or running emulator. Open
**Odoo Example** on the device after installing. The APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`.

The wrapper is already included; do not run `:app:wrapper` to launch the app.
If that error appears, open the root project directory and use an
**Android App** run configuration with the **app** module.

## Library import

In [`app/build.gradle.kts`](app/build.gradle.kts):

```kotlin
dependencies {
    implementation(files("libs/odoo-client.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

External dependencies are declared explicitly because a local AAR does not
bundle them. The library AAR is included in this project.

In [`MainActivity.kt`](app/src/main/kotlin/example/odoo/MainActivity.kt):

```kotlin
import io.github.elierwclik.odooclient.OdooClient

// Run inside a coroutine.
val client = OdooClient.rpc(
    baseUrl = "https://odoo.example.com",
    database = "my_database",
    login = "user@example.com",
    password = passwordFromUser,
)
client.checkLogin()
val contacts = client.getModel("res.partner").searchRead(
    fields = listOf("name", "email"),
    limit = 10,
)
```

## Credits

The library derives from `odoo/odoo-client-lib`. The original copyrights of
Odoo s.a., OpenERP s.a., and the original authors are preserved, along with the
BSD 3-Clause license. See [LICENSE-ODOO.txt](LICENSE-ODOO.txt) and
[NOTICE.txt](NOTICE.txt). The app bundles these texts in
`app/src/main/assets/ODOO-LICENSE.txt` and `ODOO-NOTICE.txt`, in addition to the
notices inside the AAR. This is an independent adaptation.
