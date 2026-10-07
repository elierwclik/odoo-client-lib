# Repository and local Maven publication

Android adaptation: [elierwclik/odoo-client-lib](https://github.com/elierwclik/odoo-client-lib).
Official original Python library: [odoo/odoo-client-lib](https://github.com/odoo/odoo-client-lib).

## GitHub repository

The repository root is the Android library project, with its Gradle build files,
`src/`, and `docs/`. The `example/` directory is the standalone Android app,
which imports its own compiled AAR. The root also includes `README.md`,
`LICENSE`, `NOTICE.txt`, and `.gitignore`.

Run Git commands from the repository root.
For the initial upload, after configuring the `origin` remote:

```sh
git add .
git commit -m "Add Kotlin Android Odoo client and example"
git push -u origin master
```

The remote URL is `https://github.com/elierwclik/odoo-client-lib.git`.
Uploading requires GitHub authentication and repository write access.
Build output, caches, distribution directories, local SDK paths, and signing
keys are ignored. Gradle wrappers and `example/app/libs/odoo-client.aar`
are included because they are required to build the projects.

## Update the example's AAR

From the repository root:

```sh
./gradlew assembleRelease
```

Copy `build/outputs/aar/odoo-client-release.aar` to
`example/app/libs/odoo-client.aar`, using paths relative to the repository root.
From `example/`:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

## Local Maven publication

From the repository root:

```sh
./gradlew publishReleasePublicationToLocalDistributionRepository
```

The local repository is generated at `build/repository`.
Configure that directory as a Maven repository in your app's settings:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("/path/to/build/repository") }
    }
}
```

Then add:

```kotlin
implementation("io.github.elierwclik:odoo-client-android:1.0.0")
```

Gradle resolves external dependencies using the POM and module metadata.
These coordinates describe a local publication; they have not been published
to Maven Central or GitHub Packages.

## Attribution

Keep Odoo's original license and copyright notices in source and binary
redistributions. See [attribution.md](attribution.md). The official Python
repository is referenced by link; Python source files are not bundled.
