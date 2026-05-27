plugins {
    id("kanal.kotlin-library")
    id("kanal.publish")
}

group = "io.github.big-iron-cheems"
version = "0.2.0"

dependencies {
    api(project(":kanal-core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Kanal-coroutines")
        description.set("Coroutines extensions for Kanal.")
    }
}
