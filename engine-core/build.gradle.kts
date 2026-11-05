// Platform-neutral engine: DSL models, expressions, variables, layout solver.
// Pure Kotlin/JVM so its tests run without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "engine-core"
            from(components["java"])
            pom {
                name.set("App8 Engine Core")
                description.set("Platform-neutral App8 DSL engine: models, expressions, variables and the layout solver.")
            }
        }
    }
}
