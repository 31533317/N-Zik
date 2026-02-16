enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "N-Zik"
include(":composeApp")
// Projects from extensions
include(":oldtube")
project(":oldtube").projectDir = file("extensions/innertube")
include(":kugou")
project(":kugou").projectDir = file("extensions/kugou")
include(":lrclib")
project(":lrclib").projectDir = file("extensions/lrclib")
include(":piped")
project(":piped").projectDir = file("extensions/piped")
include(":invidious")
project(":invidious").projectDir = file("extensions/invidious")
include(":ktor-client-brotli")
project(":ktor-client-brotli").projectDir = file("extensions/ktor-client-brotli")
// Submodules
include(":innertube")
project(":innertube").projectDir = file("modules/innertube")
include("discord")
project(":discord").projectDir = file("modules/discord")
include(":metrolist")
project(":metrolist").projectDir = file("modules/metrolist-innertube")