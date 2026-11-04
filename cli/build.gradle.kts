plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "coaching"

// Single source of truth for the version: the plugin manifest.
val pluginVersion: String = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
    .find(rootDir.resolve("../.claude-plugin/plugin.json").readText())
    ?.groupValues?.get(1)
    ?: "0.0.0-dev"
version = pluginVersion

val generatedSrc = layout.buildDirectory.dir("generated/buildinfo")

val generateBuildInfo by tasks.registering {
    val out = generatedSrc
    val v = pluginVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("lib/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package lib\n\nobject BuildInfo {\n    const val VERSION = \"$v\"\n}\n")
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor HTTP client
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-client-auth:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // CLI
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Date/time
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("MainKt")
    applicationName = "coach"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generatedSrc)
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
