import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Permanent release signing is local-only at this stage. The keystore and its passwords live
// outside this repository and are referenced by an ignored `keystore.properties`; only the
// placeholder `keystore.properties.example` is tracked. See docs/RELEASE.md.
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningGatedTasks = setOf(
    "preReleaseBuild",
    "packageRelease",
    "packageReleaseBundle",
    "assembleRelease",
    "bundleRelease",
)
// Values still carrying an example placeholder count as missing, so a copied but half-edited
// keystore.properties fails with the same clear message as an absent one.
val releaseSigningPlaceholders = listOf("REPLACE_WITH_", "C:/replace/with/")
val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties: Properties? = releaseSigningPropertiesFile
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    ?.takeIf { properties ->
        releaseSigningKeys.all { key ->
            val value = properties.getProperty(key)
            !value.isNullOrBlank() &&
                releaseSigningPlaceholders.none { prefix -> value.startsWith(prefix) }
        } && rootProject.file(properties.getProperty("storeFile")).isFile
    }

android {
    namespace = "dev.killua.iptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.killua.iptv"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "0.2.0-alpha.30"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        releaseSigningProperties?.let { properties ->
            create("release") {
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                // minSdk 26 is always above the API 24 v2 baseline, so the legacy JAR
                // signature adds size without adding verifiable trust.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Null until a complete local keystore.properties exists. AGP leaves such a build
            // unsigned rather than substituting the debug certificate, and
            // `verifyReleaseSigning` stops it before packaging either way.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    // Room's migration test helper reads the exported schemas from the test APK's assets.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

/**
 * Fails any release build that has no complete local signing configuration, instead of quietly
 * producing an unsigned or differently signed APK. The task reports names and status only; it
 * never reads, prints, or logs a password or key.
 */
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails a release build when the local production signing configuration is missing."
    val signingConfigured = releaseSigningProperties != null
    val propertiesFileName = releaseSigningPropertiesFile.name
    val requiredKeys = releaseSigningKeys.joinToString(", ")
    doLast {
        if (signingConfigured) return@doLast
        throw GradleException(
            """
            Release signing is not configured, so this build was stopped before packaging.

            Create an untracked $propertiesFileName in the repository root with $requiredKeys,
            pointing storeFile at an existing keystore stored outside this repository. Values left
            at their example placeholder are treated as missing. Never commit it,
            never place it in GitHub Secrets, and never paste its values into chat or logs.
            See docs/RELEASE.md. Debug builds and `assembleDebug` are unaffected.
            """.trimIndent(),
        )
    }
}

tasks.matching { it.name in releaseSigningGatedTasks }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-paging:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.paging:paging-runtime-ktx:3.4.2")
    implementation("androidx.paging:paging-compose:3.4.2")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("androidx.room:room-testing:2.8.4")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("com.google.truth:truth:1.4.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
