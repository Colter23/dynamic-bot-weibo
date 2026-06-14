plugins {
    kotlin("jvm") version "2.4.0"
}

apply(from = "gradle/dynamic-plugin-fatjar.gradle.kts")

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    val coroutinesVersion = "1.11.0"
    val coreVersion = "0.0.1"
    val kotlinLoggingVersion = "8.0.4"
    val serializationVersion = "1.11.0"

    compileOnly("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    compileOnly("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    testImplementation(kotlin("test"))
    testImplementation("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    testImplementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    jvmToolchain(21)
}
