rootProject.name = "world-domination"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":core")
include(":bot")
include(":app")
include(":androidApp")
