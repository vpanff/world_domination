// Android-приложение ведущего: только точка входа, весь UI — в модуле :app.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "wd.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.vpanff.worlddomination"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "2.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Пока подписываем отладочным ключом — для установки на свои телефоны.
            // Для публикации в магазине понадобится свой ключ.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.activity.compose)
}
