plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.layerbit.ravam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.layerbit.ravam"
        minSdk = 29          // Android 10 — the first release with the modern audio policy
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        aidl = true
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Shizuku: reach the shell UID (CAPTURE_VOICE_COMMUNICATION_OUTPUT) without root,
    // for the privileged VoIP capture tier (PLAN.md E2).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Embedded ADB: pair with the phone's own Wireless Debugging and reach the shell UID
    // from inside Ravam, with no companion app (PLAN.md §2.3, "embedded ADB").
    implementation(libs.libadb)
    implementation(libs.sun.security)
    implementation(libs.conscrypt)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}
