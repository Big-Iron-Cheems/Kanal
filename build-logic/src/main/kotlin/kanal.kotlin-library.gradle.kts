plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            exclude {
                byNames.add("io.github.bigironcheems.kanal.internal.**")
            }
        }
    }
}

java {
    withSourcesJar()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
