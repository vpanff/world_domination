import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Общий UI приложения ведущего: ПК (desktop) и библиотека для androidApp.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
            api(project(":core"))
            api(project(":bot"))   // HostSession наследует GameHost — нужен потребителям
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
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
            packageName = "World Domination"
            packageVersion = "2.1.0"
            // WiX на Windows не принимает кириллицу при кодовой странице 1252.
            description = if ("win" in System.getProperty("os.name").lowercase()) "World Domination" else "Мировое господство"
            vendor = "vpanff"
            // Упакованный JRE урезан: instrument и unsupported подсказал
            // suggestRuntimeModules, crypto.ec нужен боту для HTTPS к Telegram.
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec")
            linux {
                packageName = "world-domination"
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                shortcut = true
                menuGroup = "Game"
                appCategory = "games"
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menu = true
                shortcut = true
                // Постоянный: по нему новая версия заменяет старую, а не ставится рядом.
                upgradeUuid = "727fea47-b5d9-4845-a6ae-b5a2be6d2f39"
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "io.github.vpanff.worlddomination"
            }
        }
    }
}
