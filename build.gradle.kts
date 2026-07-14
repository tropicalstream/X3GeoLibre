plugins {
    // Bumped from 8.7.3 for GeckoView's compileSdk-36 / androidx.core 1.18 chain.
    id("com.android.application") version "8.9.1" apply false
    // Bumped from 2.0.21 to match kotlin-stdlib 2.3.21 pulled by the newer androidx.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
