plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    `maven-publish`
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

// Compiles examples source set against the main library output but is excluded from
// all published artifacts, ABI validation, and explicitApi enforcement.
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
    explicitApi() // enforce public API documentation discipline

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        filters {
            excluded {
                // Internal implementation classes are not part of the public ABI.
                byNames.add("io.github.bigironcheems.kanal.internal.**")
            }
        }
    }
}

java {
    withSourcesJar()
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    from(tasks.dokkaGeneratePublicationJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
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
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(dokkaJavadocJar)
            pom {
                name = "Kanal"
                description = "A Kotlin-first, Java-compatible event-handler library for JDK 25."
                url = "https://github.com/big-iron-cheems/Kanal"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "big-iron-cheems"
                        name = "BigIronCheems"
                        url = "https://github.com/big-iron-cheems"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/big-iron-cheems/Kanal.git"
                    developerConnection = "scm:git:ssh://github.com/big-iron-cheems/Kanal.git"
                    url = "https://github.com/big-iron-cheems/Kanal"
                }
            }
        }
    }
    // Uncomment when ready to publish to Maven Central:
    /*repositories {
        maven("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/") {
            name = "MavenCentral"

            credentials {
                username = requireNotNull(System.getenv("MAVEN_CENTRAL_USERNAME")) {
                    "Set MAVEN_CENTRAL_USERNAME in your environment"
                }
                password = requireNotNull(System.getenv("MAVEN_CENTRAL_PASSWORD")) {
                    "Set MAVEN_CENTRAL_PASSWORD in your environment"
                }
            }
        }
    }*/
}
