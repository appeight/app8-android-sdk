// The Android renderer: turns DSL into Jetpack Compose UI.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "dev.app8.engine"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    // Pre-existing findings live in lint-baseline.xml; anything new fails `:engine:lint`.
    lint { baseline = file("lint-baseline.xml") }
    // Style mapping tests touch Compose value types only; android.jar stubs return defaults.
    testOptions { unitTests.isReturnDefaultValues = true }
    // Pipeline tests run on the JVM; android.jar stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
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
    api(project(":engine-core"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.browser)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "engine"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("App8 Engine")
                description.set("Renders App8 DSL apps natively with Jetpack Compose.")
            }
        }
    }
}
