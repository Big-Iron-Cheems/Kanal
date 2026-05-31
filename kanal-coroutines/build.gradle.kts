plugins {
    id("kanal.kotlin-library")
    id("kanal.publish")
    id("kanal.benchmark")
}

version = libs.versions.project.get()

dependencies {
    api(project(":kanal-core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
    jmhAnnotationProcessor(libs.jmh.annprocess)
}

val examples: SourceSet by sourceSets.creating {
    kotlin.srcDir("src/examples/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

tasks.named("check") {
    dependsOn("compileExamplesKotlin")
}

mavenPublishing {
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Kanal-coroutines")
        description.set("Coroutines extensions for Kanal.")
    }
}
