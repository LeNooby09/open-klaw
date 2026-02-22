plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("application")
}

group = "tech.lenooby09"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.2"

dependencies {
    // Koog AI agent framework
    implementation("ai.koog:koog-agents:0.6.2")

    // Ktor server (CIO engine — avoids Netty classpath conflicts)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    // BCrypt for password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("tech.lenooby09.openklaw.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
