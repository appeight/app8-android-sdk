// Sample host and preview host: renders a DSL bundle on a device or emulator.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.app8.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.app8.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0"
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
}
