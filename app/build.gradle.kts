plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val adsMode = System.getenv("BUTONE_ADS_MODE").orEmpty().ifEmpty { "off" }
val playBundle = providers.gradleProperty("playBundle").orNull == "true"
if (playBundle) {
    require(adsMode != "test") { "Test ads must not be packaged for Play" }
    layout.buildDirectory.set(layout.projectDirectory.dir("build/play-bundle"))
}
require(adsMode in setOf("off", "test", "live")) { "Invalid BUTONE_ADS_MODE" }
val sampleApp = "ca-app-pub-3940256099942544~3347511713"
val sampleBanner = "ca-app-pub-3940256099942544/9214589741"
val sampleReward = "ca-app-pub-3940256099942544/5224354917"
val adsApp = if (adsMode == "live") System.getenv("ADMOB_APP_ID").orEmpty() else sampleApp
val adsBanner = if (adsMode == "live") System.getenv("ADMOB_BANNER_ID").orEmpty() else sampleBanner
val adsReward = if (adsMode == "live") System.getenv("ADMOB_REWARDED_ID").orEmpty() else sampleReward
if (adsMode == "live") {
    require(Regex("ca-app-pub-[0-9]{16}~[0-9]+").matches(adsApp)) { "Invalid ADMOB_APP_ID" }
    val unitPattern = Regex("ca-app-pub-[0-9]{16}/[0-9]+")
    require(unitPattern.matches(adsBanner) && unitPattern.matches(adsReward) &&
        adsBanner != adsReward && listOf(adsBanner, adsReward).all { it.substringBefore('/') == adsApp.substringBefore('~') } &&
        !adsApp.startsWith("ca-app-pub-3940256099942544")
    ) { "Live ad units must be distinct, real and owned by the configured publisher" }
}

android {
    namespace = "app.butone.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.butone.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "21").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.21"
    }

    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "IS_TV", "false")
            manifestPlaceholders["phoneLauncherEnabled"] = "true"
            manifestPlaceholders["tvLauncherEnabled"] = "false"
            manifestPlaceholders["adMobAppId"] = adsApp
            buildConfigField("boolean", "ADS_ENABLED", (adsMode != "off").toString())
            buildConfigField("boolean", "ADS_TEST", (adsMode == "test").toString())
            buildConfigField("String", "AD_BANNER_ID", "\"$adsBanner\"")
            buildConfigField("String", "AD_REWARDED_ID", "\"$adsReward\"")
            if (adsMode == "test") {
                applicationIdSuffix = ".adstest"
                versionNameSuffix = "-ads-test"
            }
        }
        create("tv") {
            dimension = "device"
            targetSdk = 35
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = !playBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Mihomo core, built by gomobile from ../core (see .github/workflows/build.yml)
    implementation(files("libs/core.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Keep the Fragment implementation that can be pulled in by SDKs compatible
    // with the Activity Result API used for VPN and notification permissions.
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    add("phoneImplementation", "com.google.android.gms:play-services-ads:25.5.0")
    add("phoneImplementation", "com.google.android.ump:user-messaging-platform:4.0.0")
}
