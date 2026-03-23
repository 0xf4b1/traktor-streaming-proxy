plugins {
    kotlin("jvm") version "2.1.20"
    application
    kotlin("plugin.serialization").version("2.1.20")
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

configurations.all {
    // Exclude protobuf-javalite - it conflicts with protobuf-java needed by librespot
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    resolutionStrategy {
        force("com.google.protobuf:protobuf-java:3.25.5")
    }
}

dependencies {
    val ktor_version = "3.1.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor_version")
    implementation("org.slf4j:slf4j-log4j12:2.0.6")

    implementation("com.github.teamnewpipe.NewPipeExtractor:extractor:v0.24.8")
    implementation("com.github.librespot-org.librespot-java:librespot-lib:52a8c24215")
    implementation("com.github.0xf4b1:spotify-kt:275f290e64")
    implementation("com.github.0xf4b1:tidal-kt:v0.3.1")

    implementation("org.json:json:20250107")
    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("MainKt")
}