plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.lacelang"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.lacelang.validator.CliKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
