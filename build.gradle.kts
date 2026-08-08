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
    val ktorVersion = "3.1.3"
    implementation(enforcedPlatform("io.netty:netty-bom:4.1.137.Final"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-xml:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-cbor:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-network-tls-certificates:$ktorVersion")
    implementation("org.slf4j:slf4j-log4j12:2.0.6")

    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.4")
    implementation("com.github.librespot-org.librespot-java:librespot-lib:52a8c24215")
    implementation("com.github.0xf4b1:spotify-kt:275f290e64")
    implementation("com.github.0xf4b1:tidal-kt:v0.3.1")

    // Patched transitive protobuf version (IDE Mend warning).
    constraints {
        implementation("com.google.protobuf:protobuf-java:3.25.9")
    }

    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("MainKt")
}
