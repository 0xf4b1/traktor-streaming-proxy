import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.20"
    application
    kotlin("plugin.serialization").version("1.8.0")
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    val ktor_version = "3.0.0"
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-xml:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-cbor:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor_version")
    implementation("org.slf4j:slf4j-log4j12:2.0.6")

    implementation("com.github.teamnewpipe.NewPipeExtractor:extractor:v0.24.6")

    implementation("xyz.gianlu.librespot:librespot-lib:1.6.4")
    implementation("io.github.tiefensuche:spotify-kt:0.1")
    implementation("com.tiefensuche:tidal-kt:0.2.0")

    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/0xf4b1/tidal-kt")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
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
