import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Общий UI приложения ведущего: ПК (desktop) и библиотека для androidApp.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvm("desktop")

    android {
        namespace = "wd.app"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        // Общий код для ПК и Android (работа с файлами)
        val jvmSharedMain = create("jvmSharedMain") { dependsOn(commonMain.get()) }
        getByName("desktopMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "wd.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "world-domination"
            packageVersion = "2.0.0"
        }
    }
}
