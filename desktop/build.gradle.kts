import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.koin.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.photohub.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Silly"
            packageVersion = "1.0.0"
            description = "A minimalist image manager that pulls from a mosaic of sources"
            vendor = "Silly"

            macOS {
                iconFile.set(File(project.projectDir, "icons/icon.icns"))
            }
            windows {
                iconFile.set(File(project.projectDir, "icons/icon.ico"))
            }
            linux {
                iconFile.set(File(project.projectDir, "icons/icon.png"))
            }
        }
    }
}
