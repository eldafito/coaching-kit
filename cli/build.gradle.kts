plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "coaching"
version = "0.1.0"

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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
