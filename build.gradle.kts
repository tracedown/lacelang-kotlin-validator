import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "dev.lacelang"
version = "0.1.2"

repositories {
    mavenLocal()
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

// The shadow plugin registers a `shadowRuntimeElements` variant that carries the
// fat CLI jar. Keep the fat jar for the GitHub release, but never publish it to
// Maven Central — consumers resolve the thin jar and its POM dependencies.
(components["java"] as org.gradle.api.component.AdhocComponentWithVariants)
    .withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("dev.lacelang", "kotlin-validator", version.toString())

    pom {
        name.set("Lace Kotlin Validator")
        description.set(
            "Kotlin validator for the Lace probe scripting language — lexer, parser, and semantic validator.",
        )
        inceptionYear.set("2026")
        url.set("https://lacelang.dev")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("lacelang")
                name.set("Lace")
                url.set("https://lacelang.dev")
            }
        }
        scm {
            url.set("https://github.com/tracedown/lacelang-kotlin-validator")
            connection.set("scm:git:https://github.com/tracedown/lacelang-kotlin-validator.git")
            developerConnection.set("scm:git:ssh://git@github.com/tracedown/lacelang-kotlin-validator.git")
        }
    }
}
