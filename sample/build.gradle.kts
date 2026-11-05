// Sample host and preview host: renders a DSL bundle on a device or emulator.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.app8.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.app8.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }
    buildTypes {
        // Debug-signed so `installRelease` works for frame-time measurements (debuggable
        // Compose builds run several times slower and say nothing about real jank).
        release {
            signingConfig = signingConfigs.getByName("debug")
            // As hosts ship it: R8 with the engine's consumer rules.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.activity.compose)
    // Installs the engine's Baseline Profile on sideloaded builds (Play does it for store installs).
    implementation(libs.profileinstaller)
    baselineProfile(project(":benchmark"))
}

baselineProfile {
    // The engine AAR carries the profile that matters; the sample's own is for local runs.
    automaticGenerationDuringBuild = false
}
