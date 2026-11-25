plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    // expect/actual classes are still flagged Beta; we use them deliberately.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // AGP 9: the Android target is declared through the KMP library plugin
    androidLibrary {
        namespace = "dev.photohub.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            // Compose
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            // graphicsLayer (pinch-zoom transform) and onPointerEvent (desktop
            // scroll-to-zoom) live in org.jetbrains.compose.ui:ui, which the
            // `compose.*` alias block does not expose directly.
            implementation("org.jetbrains.compose.ui:ui:${libs.versions.compose.multiplatform.get()}")

            // Lifecycle / ViewModel (KMP flavour from JetBrains)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)

            // Coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Serialization + time
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Networking
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)

            // Database
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            // DI
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Filesystem
            implementation(libs.okio)

            // MEGA (M4): vendored kmp-mega source, patched for legacy accounts.
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.logging)
            implementation(libs.cryptography.core)
            implementation(libs.bignum)
            implementation(libs.stately.concurrency)

            // NAS archive (M6): SFTP over SSH. Both PhotoHub targets (Android,
            // desktop) are JVM, so this pure-Java library resolves from
            // commonMain without a platform-specific wrapper.
            implementation(libs.sshj)

            // HEIC/HEIF thumbnails (M7): same reasoning as sshj above — pure
            // Java, no native code, so one decode path covers both targets.
            implementation(libs.openize.heic)

            // Image loading
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.koin.android)
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.exifinterface)
            // BackHandler for the fullscreen viewer's system-back dismissal.
            implementation(libs.androidx.activity.compose)
            // Video playback in the fullscreen viewer.
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.java)
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.sqldelight.sqlite.driver)
            // Video playback: JavaFX media embedded via SwingPanel. JavaFX ships
            // no cross-platform artifact, only one native-classifier jar per
            // OS/arch, so the classifier for the machine running the build is
            // appended directly to the coordinate below.
            val fxVersion = libs.versions.javafx.get()
            val fx = javafxClassifier()
            implementation("org.openjfx:javafx-base:$fxVersion:$fx")
            implementation("org.openjfx:javafx-graphics:$fxVersion:$fx")
            implementation("org.openjfx:javafx-media:$fxVersion:$fx")
            implementation("org.openjfx:javafx-swing:$fxVersion:$fx")
        }

        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}

sqldelight {
    databases {
        create("PhotoHubDatabase") {
            packageName.set("dev.photohub.shared.db")
            // Default dialect is SQLite 3.18, which has no UPSERT (ON CONFLICT DO UPDATE).
            // 3.38 matches Android API 34+ and modern desktop SQLite.
            dialect(libs.sqldelight.dialect.sqlite)
            // Dump the generated schema so future migrations can be diffed against it.
            // verifyMigrations stays off until the first .sqm migration file exists.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}

/**
 * JavaFX's Maven artifacts ship one jar per OS/arch rather than a single
 * cross-platform jar, keyed by this Maven classifier. Desktop app packaging
 * already builds one installer per OS (see [compose.desktop.application] in
 * :desktop), so picking the classifier for the machine running the build
 * matches how the rest of the project already handles platform-specific
 * native code.
 */
fun javafxClassifier(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val arch = System.getProperty("os.arch")
    return when {
        os.isMacOsX -> if (arch == "aarch64") "mac-aarch64" else "mac"
        os.isWindows -> "win"
        arch == "aarch64" -> "linux-aarch64"
        else -> "linux"
    }
}
