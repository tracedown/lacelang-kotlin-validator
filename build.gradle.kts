import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// group and version come from gradle.properties (single source of truth).

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

// ── Version single-source ──
// The version lives only in gradle.properties. Generate the runtime VERSION
// constant from it so the CLI banner can never drift from the released version.
val generateVersionInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/lacelang/validator/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from the project version (gradle.properties) — do not edit.\n" +
                "package dev.lacelang.validator\n\nconst val VERSION = \"$ver\"\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateVersionInfo) }

// Name the fat jar without a version so lace-executor.toml and the release
// workflow reference it by a stable path.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("lacelang-kt-validator-all.jar")
}

// Fail the build if the Lace manifest's version drifts from the project version.
val verifyManifestVersion by tasks.registering {
    doLast {
        val declared = Regex("""(?m)^version\s*=\s*"([^"]+)"""")
            .find(file("lace-executor.toml").readText())?.groupValues?.get(1)
        require(declared == project.version.toString()) {
            "lace-executor.toml version ($declared) != project version (${project.version}) — update lace-executor.toml."
        }
    }
}
tasks.named("check") { dependsOn(verifyManifestVersion) }

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
