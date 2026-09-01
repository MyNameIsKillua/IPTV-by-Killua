/*
 * Platform-neutral rules, shared by the Android app and any later client.
 *
 * A plain Kotlin JVM library rather than a Kotlin Multiplatform module, deliberately and for now.
 * What lives here has no external dependency at all, so a JVM library already serves Android,
 * Windows and macOS; a multiplatform module would only start paying for itself when iOS arrives, and
 * that also needs Ktor in place of Retrofit. Converting this module later is mechanical - the plugin
 * changes and `src/main/kotlin` becomes `src/commonMain/kotlin`.
 *
 * Every dependency here has to be argued for. `paging-common` earned its place because the
 * repository contracts return `PagingData` and it is a plain Kotlin artifact, unlike
 * `paging-runtime`. Room and Retrofit types are what keep the data layer in `:app`; see
 * `docs/ARCHITECTURE.md`.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Only for the export format, which is a schema this project owns and therefore describes with
    // @Serializable classes. The provider parser deliberately stays tree-based: its input is
    // whatever a provider felt like sending.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Multiplatform-safe: paging-common is a plain Kotlin artifact, unlike paging-runtime.
    // The repository contracts return PagingData, so it travels with them.
    api("androidx.paging:paging-common:3.4.2")
    // Multiplatform-safe. The provider parser works on the JSON tree rather than @Serializable
    // classes, so the compiler plugin is not needed here.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Only for HttpUrl, as a URL builder and parser - never as an HTTP client. JVM-only, and
    // therefore the one thing in this module an iOS target would have to replace. See CLAUDE.md.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.google.truth:truth:1.4.5")
}
