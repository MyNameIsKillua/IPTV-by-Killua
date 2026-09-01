/*
 * The Windows/macOS client. Compose Multiplatform for the UI, libvlc through vlcj for playback.
 *
 * Why those, and what it cost to find out, is in `docs/ROADMAP.md`: a throwaway spike measured 50 of
 * 50 frames presented at 3840x2176 with Compose UI in the same scene, using 22% of the per-frame
 * budget. The decisive detail is that VLC hands over its decoder's own I420 planes and the colour
 * conversion happens in a Skia shader; asking VLC for BGRA instead costs about 28ms a frame and caps
 * the whole pipeline at 19fps.
 *
 * This module depends on `:shared` and never on `:app`. Android stays the reference implementation.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    // Only for the small title cache beside the state file; see TitleIndex.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

/*
 * The version, written once.
 *
 * It has to reach two places that cannot see each other: the installer's ProductVersion, and the
 * running program, which needs it to tell whether a published release is newer than itself. Two
 * hand-maintained copies of a version number is a client that eventually offers an update to the
 * version it already is, so the second one is generated from the first.
 */
val appVersion = "1.0.4"

val generateVersionResource by tasks.registering {
    // Both are read into locals here, at configuration time, and only the locals are captured by
    // the action below. Referring to `appVersion` or to `layout` from inside `doLast` would capture
    // the build script itself, which the configuration cache cannot serialise - it fails the build
    // rather than silently disabling itself, which is how this was found.
    val output = layout.buildDirectory.file("generated/version/app-version.txt")
    val version = appVersion
    inputs.property("version", version)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(version)
        }
    }
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}

tasks.named("processResources") { dependsOn(generateVersionResource) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // vlcj 4.x is the line that pairs with VLC 3.x. VLC has to be installed; the app does not bundle
    // it, and a missing libvlc is reported rather than crashed on.
    implementation("uk.co.caprica:vlcj:4.8.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // For the playlist reader's redirect rules, which are the part worth testing against a
    // real socket rather than a stub: same version as `:app` uses, and only ever a test.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

/*
 * Tests never touch the owner's own data directory.
 *
 * `DesktopUserData.defaultDirectory()` reads `LOCALAPPDATA`, and anything that exercises the stores
 * for real - the artwork cache, the state file - would otherwise read and write
 * `%LOCALAPPDATA%\KilluaIPTV`, which holds someone's actual watch history. Pointing the variable at
 * a build directory makes that impossible rather than merely unlikely.
 */
tasks.withType<Test>().configureEach {
    environment("LOCALAPPDATA", layout.buildDirectory.dir("test-appdata").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "dev.killua.iptv.desktop.MainKt"

        nativeDistributions {
            // An installer *and* the plain app image. The zip of the image is what every release
            // through alpha 38 shipped and is still the way to run this without installing
            // anything; the MSI is the way to install it. Building the MSI needs the WiX toolset
            // on PATH - see `docs/RELEASE.md` - while the app image needs nothing but the JDK, so
            // `createDistributable` keeps working on a machine without WiX.
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
            )
            packageName = "Killua IPTV"
            /*
             * Three numeric fields and nothing else: an MSI ProductVersion has no room for
             * `-alpha.39`, and Windows decides upgrade-versus-downgrade by comparing them. The
             * third field is therefore the alpha number, which only ever increases, so installing
             * a later build replaces the earlier one instead of refusing or duplicating it.
             *
             * Bump this with the tag. `docs/RELEASE.md` lists it beside the Android version code.
             *
             * At 1.0.0 the alpha number stopped being the third field, because there is no longer an
             * alpha number. What has to hold is only that it never decreases, and 1.0.0 is above
             * every 0.2.x that came before it.
             */
            packageVersion = appVersion
            description = "A private-by-design IPTV client for your own Xtream-compatible account."
            vendor = "MyNameIsKillua"
            copyright = "MyNameIsKillua"

            windows {
                // Stable across rebuilds, so an upgrade replaces the app rather than installing a
                // second copy beside it.
                upgradeUuid = "8f2b8b3e-8a6a-4b4a-9a1e-6f0f1c2d3e4b"
                console = false
                // Five sizes in one file, each a PNG inside the ICO container, so Windows picks the
                // right one for the taskbar, the desktop and the alt-tab strip rather than scaling
                // a single bitmap into mush.
                iconFile.set(project.file("icon.ico"))

                /*
                 * Asks jpackage for a per-user installation into `%LOCALAPPDATA%\Killua IPTV`,
                 * and **only half of that arrives**. Verified on 26 August 2026 against a built
                 * package: the install directory is `LocalAppDataFolder`, but the MSI carries
                 * neither `MSIINSTALLPERUSER` nor `ALLUSERS`, and an installed build registers
                 * its uninstall entry under `HKLM` - so Windows still asks for administrator
                 * rights. This setting moves the directory, not the scope. Left in because the
                 * directory is right and worth keeping; the scope is open work, and
                 * `docs/RELEASE.md` says so rather than letting this comment promise it.
                 *
                 * Uninstalling removes the program. It deliberately does not remove that data
                 * directory: it holds the viewer's watch history, and an uninstaller that silently
                 * deletes a history is a bug even when it is documented.
                 */
                perUserInstall = true
                dirChooser = true
                menu = true
                menuGroup = "Killua IPTV"
                shortcut = true
            }

            // The JDK modules the app actually needs. jpackage links only these into the bundled
            // runtime, which is the difference between a ~90MB folder and a ~300MB one.
            //   java.naming  - required by OkHttp
            //   java.sql     - pulled in by Skiko and vlcj's JNA usage
            //   jdk.unsupported - sun.misc.Unsafe, which Kotlin coroutines and JNA still use
            modules("java.naming", "java.sql", "jdk.unsupported", "java.management")
        }
    }
}
