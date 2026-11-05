plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

// The release version has one source: EngineVersion.CURRENT. The git tag, the Maven version
// and the iOS EngineVersion.current all equal it (app8-dsl VERSIONING.md).
val engineVersion: String = run {
    val source = file("engine-core/src/main/kotlin/dev/app8/engine/core/EngineVersion.kt").readText()
    Regex("""CURRENT:\s*String\s*=\s*"([^"]+)"""").find(source)?.groupValues?.get(1)
        ?: error("EngineVersion.CURRENT not found in EngineVersion.kt")
}

allprojects {
    group = "dev.app8"
    version = engineVersion
}

// Public API dumps (`api/<module>.api`). `./gradlew apiCheck` fails on unreviewed API changes;
// `./gradlew apiDump` records intended ones.
apiValidation {
    ignoredProjects += listOf("sample", "benchmark")
    nonPublicMarkers += "kotlin.PublishedApi"
}

// BCV 0.18 configures JVM, multiplatform and `kotlin-android` projects. AGP 9 compiles Kotlin
// itself (no `kotlin-android` plugin), so BCV skips the Android libraries; register its tasks
// here, over the release variant's classes, under the same names (apiBuild/apiCheck/apiDump).
val bcvKotlinVersion: String = libs.versions.kotlin.get()
subprojects {
    pluginManager.withPlugin("com.android.library") {
        val bcvRuntime = configurations.create("bcvAndroidRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies {
            add(bcvRuntime.name, "org.ow2.asm:asm:9.6")
            add(bcvRuntime.name, "org.ow2.asm:asm-tree:9.6")
            add(bcvRuntime.name, "org.jetbrains.kotlin:kotlin-metadata-jvm:$bcvKotlinVersion")
        }
        val dumpFile = layout.projectDirectory.file("api/${project.name}.api")
        val apiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
            group = "verification"
            description = "Builds the public API dump of the release variant into build/api."
            runtimeClasspath.from(bcvRuntime)
            inputClassesDirs.from(
                tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileReleaseKotlin")
                    .flatMap { it.destinationDirectory },
            )
            outputApiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
        }
        val apiCheck = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
            group = "verification"
            description = "Fails when the public API differs from api/${project.name}.api."
            projectApiFile.set(dumpFile)
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
        }
        tasks.register<Copy>("apiDump") {
            group = "other"
            description = "Records the current public API in api/${project.name}.api."
            from(apiBuild.flatMap { it.outputApiFile })
            into(layout.projectDirectory.dir("api"))
        }
        tasks.named("check") { dependsOn(apiCheck) }
    }
}

// Shared Maven coordinates, POM and the GitHub Packages repository for every published module.
// Each module applies `maven-publish` and declares its own publication.
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/appeight/app8-android-sdk")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    }
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/appeight/app8-android-sdk")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("appeight")
                            name.set("App8 Ltd")
                            url.set("https://github.com/appeight")
                        }
                    }
                    scm {
                        url.set("https://github.com/appeight/app8-android-sdk")
                        connection.set("scm:git:https://github.com/appeight/app8-android-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/appeight/app8-android-sdk.git")
                    }
                }
            }
        }
    }
}
