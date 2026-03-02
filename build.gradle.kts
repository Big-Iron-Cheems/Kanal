plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.big-iron-cheems"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.kotlin.test)

    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
    jmhAnnotationProcessor(libs.jmh.annprocess)
}

val examples: SourceSet by sourceSets.creating {
    kotlin.srcDir("src/examples/kotlin")
    java.srcDir("src/examples/java")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

tasks {
    named("check") {
        dependsOn("checkLegacyAbi")
        dependsOn("compileExamplesKotlin", "compileExamplesJava")
    }

    test {
        useJUnitPlatform()
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        filters {
            excluded {
                byNames.add("io.github.bigironcheems.kanal.internal.**")
            }
        }
    }
}

java {
    withSourcesJar()
}

jmh {
    jmhVersion = libs.versions.jmh.get()
    resultFormat = "JSON"

    if (project.hasProperty("jmhInclude")) {
        val filter = project.property("jmhInclude") as String
        includes.add(filter)
        resultsFile = layout.buildDirectory.file("reports/jmh/results-$filter.json")
    } else {
        resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    }
}

publishing {
    repositories {
        maven("https://maven.meteordev.org/") {
            name = "meteordev"

            credentials {
                username = System.getenv("MAVEN_METEOR_ALIAS")
                password = System.getenv("MAVEN_METEOR_TOKEN")
            }

            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), name.toString(), version.toString())

    signAllPublications()

    pom {
        name.set("Kanal")
        description.set("A Kotlin-first, Java-compatible event-handler library.")
        url.set("https://github.com/big-iron-cheems/Kanal")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("big-iron-cheems")
                name.set("Big Iron")
                url.set("https://github.com/big-iron-cheems")
            }
        }

        scm {
            url.set("https://github.com/big-iron-cheems/Kanal")
            connection.set("scm:git:git://github.com/big-iron-cheems/Kanal.git")
            developerConnection.set("scm:git:ssh://github.com/big-iron-cheems/Kanal.git")
        }
    }
}
