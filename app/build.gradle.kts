plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.butone.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.butone.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "14").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.14"
    }

    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "IS_TV", "false")
            manifestPlaceholders["phoneLauncherEnabled"] = "true"
            manifestPlaceholders["tvLauncherEnabled"] = "false"
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
            buildConfigField("boolean", "IS_TV", "true")
            manifestPlaceholders["phoneLauncherEnabled"] = "false"
            manifestPlaceholders["tvLauncherEnabled"] = "true"
        }
    }

    // The Mihomo core (libgojni.so, ~64 MB) is otherwise stored uncompressed;
    // compressing it cuts the download several times at the cost of extraction on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // No 32-bit x86 core is built (see gomobile -target in CI); drop the
            // stray androidx lib so x86 devices fail at install, not at runtime.
            excludes += "lib/x86/**"
        }
    }

    // Release signing is configured only when the keystore secrets are present (CI).
    val ksPath = System.getenv("KEYSTORE_PATH")
    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Mihomo core, built by gomobile from ../core (see .github/workflows/build.yml)
    implementation(files("libs/core.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
