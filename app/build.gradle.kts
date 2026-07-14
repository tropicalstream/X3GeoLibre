import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.x3geolibre.app"
    // GeckoView's transitive androidx.media3 requires compiling against API 36.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.x3geolibre.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // The X3 Pro is arm64-v8a; ship only that GeckoView native lib to keep the
        // APK to one architecture instead of ~4x.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // Bundled modern browser engine (Firefox 152) — the X3 Pro's system WebView is
    // stuck at Chrome 95 and can't be replaced on this locked build, so we ship our
    // own engine for WASM / modern CSS / correct rendering.
    implementation("org.mozilla.geckoview:geckoview:152.0.20260706120035")

    // RayNeo X3 Pro SDKs (dual-projection / Mercury launcher integration).
    // Packaged at runtime (implementation, not compileOnly) since TapGPT is
    // a standalone app and nothing else provides them.
    implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    implementation(files("libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))
}
