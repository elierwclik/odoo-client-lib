plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.21"
    id("maven-publish")
}

group = "io.github.elierwclik"
version = "1.1.0"

android {
    namespace = "io.github.elierwclik.odooclient"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

// Source redistributions must carry the complete original license as well.
tasks.withType<Jar>().configureEach {
    if (name == "sourceReleaseJar") {
        from("src/main/resources")
        from("docs") { into("documentation") }
        from("README.md") {
            into("documentation")
            rename { "LIBRARY.md" }
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "odoo-client-android"
                pom {
                    name.set("Odoo Client Library — Android Kotlin adaptation")
                    description.set("Android/Kotlin adaptation maintained by elierwclik, based on https://github.com/odoo/odoo-client-lib. Original copyrights retained.")
                    url.set("https://github.com/elierwclik/odoo-client-lib")
                    scm {
                        url.set("https://github.com/elierwclik/odoo-client-lib")
                        connection.set("scm:git:https://github.com/elierwclik/odoo-client-lib.git")
                        developerConnection.set("scm:git:ssh://git@github.com/elierwclik/odoo-client-lib.git")
                    }
                    developers {
                        developer {
                            id.set("elierwclik")
                            name.set("elierwclik")
                            url.set("https://github.com/elierwclik")
                        }
                    }
                    licenses {
                        license {
                            name.set("BSD 3-Clause")
                            url.set("https://github.com/odoo/odoo-client-lib/blob/master/licence.txt")
                            distribution.set("repo")
                        }
                    }
                }
            }
        }
        repositories { maven { name = "localDistribution"; url = uri(layout.buildDirectory.dir("repository")) } }
    }
}
