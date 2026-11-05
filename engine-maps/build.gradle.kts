// Optional: draws App8 `map` components with Google Maps (maps-compose). Hosts that include
// this module pass `GoogleMapsProvider()` in `App8Config.mapProvider` and set their Maps API
// key (`com.google.android.geo.API_KEY` meta-data) in the app manifest.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.app8.engine.maps.google"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    explicitApi()
}

dependencies {
    api(project(":engine"))
    implementation(libs.maps.compose)
}
