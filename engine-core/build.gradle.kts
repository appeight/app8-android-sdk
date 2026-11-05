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

// Layout solver timing harness (LayoutSolverBenchmark): skipped by `test`, run on demand.
tasks.register<Test>("layoutBenchmark") {
    group = "verification"
    description = "Times solveLayout on the benchmark fixtures (results in docs/performance.md)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*LayoutSolverBenchmark") }
    systemProperty("app8.bench", "true")
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
