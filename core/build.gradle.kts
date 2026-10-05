import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }

// Deliberately a plain JVM module with no Android dependency.
//
// Everything Ravam claims about a recording is decided in here, so everything in here
// has to be provable without a handset: run `./gradlew :core:test` on any machine and
// the verdict engine is exercised against synthetic fixtures in seconds. A measurement
// you can only check by making a phone call is not a measurement.
//
// No `jvmToolchain` on purpose — that would demand one exact JDK be installed and fail
// everywhere else. Pinning the *output* to 17 is what Android actually needs; which JDK
// produced it is not Ravam's business.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies { testImplementation(libs.junit) }
tasks.test { useJUnit(); testLogging { events("passed", "failed", "skipped") } }
