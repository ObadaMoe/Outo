plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.leopard.cockpit"
    compileSdk = 36

    defaultConfig {
        // Kept from the first test build so the car updates the same app.
        applicationId = "app.leopard.cockpit"
        minSdk = 29
        // The car runs Android 13 (API 33).
        targetSdk = 33
        versionCode = 27
        versionName = "0.5.13"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the same key as the test builds, so it installs over the copy
            // already in the car. Not debuggable, so it starts and scrolls several times
            // faster than the debug build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Apple-style Liquid Glass (lens, blur, vibrancy, highlight) and continuous corners.
    implementation("io.github.kyant0:backdrop:1.0.4")
    implementation("io.github.kyant0:capsule:2.1.2")

    // The pairing code for the iPhone, as a QR code.
    implementation("com.google.zxing:core:3.5.3")

    // Talks to the car's own debugging service on 127.0.0.1 to start the helper.
    implementation("dev.mobile:dadb:2.0.0")

    testImplementation("junit:junit:4.13.2")
}
