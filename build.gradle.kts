import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.0"
    application
    kotlin("plugin.serialization").version("1.8.0")
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {

    implementation("io.ktor:ktor-server-core-jvm:2.2.3")
    implementation("io.ktor:ktor-server-netty-jvm:2.2.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.2.3")
    implementation("io.ktor:ktor-server-default-headers-jvm:2.2.3")
    implementation("io.ktor:ktor-server-content-negotiation:2.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-xml:2.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-cbor:2.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf:2.2.3")
    implementation("io.ktor:ktor-client-content-negotiation:2.2.3")
    implementation("io.ktor:ktor-server-call-logging:2.2.3")
    implementation("org.slf4j:slf4j-log4j12:2.0.6")

    implementation("com.github.TeamNewPipe.NewPipeExtractor:extractor:v0.22.2")

    implementation("xyz.gianlu.librespot:librespot-lib:1.6.3")
    implementation("org.json:json:20230227")

    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}