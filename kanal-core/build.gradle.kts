plugins {
    id("kanal.kotlin-library")
    id("kanal.publish")
    id("kanal.benchmark")
}

version = libs.versions.project.get()

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

tasks.named("check") {
    dependsOn("compileExamplesKotlin", "compileExamplesJava")
}

mavenPublishing {
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Kanal")
        description.set("A Kotlin-first, Java-compatible event-handler library.")
    }
}
