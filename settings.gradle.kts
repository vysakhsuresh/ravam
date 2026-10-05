pluginManagement {
    repositories {
        google { content {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("com\\.google.*")
            includeGroupByRegex("androidx.*")
        } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Content-filter Google's Maven to Android artifacts only. Without this, Gradle
        // tries it first for everything — including kotlin-stdlib — and any environment
        // that cannot reach dl.google.com fails to resolve dependencies that live
        // perfectly happily on Maven Central.
        google { content {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("com\\.google.*")
            includeGroupByRegex("androidx.*")
        } }
        mavenCentral()
    }
}

rootProject.name = "Ravam"

// :core is the measurement engine — plain Kotlin, no Android, testable on any JVM.
include(":core")

// :app needs the Android SDK. Include it only when one is actually present, so that
// `./gradlew :core:test` works on a bare machine, in CI, and in a sandbox. The verdict
// engine is the part most worth testing and it must never be gated behind an SDK
// install or behind network access to Google's Maven.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — configuring :core only. Set ANDROID_HOME to build the app.")
}
