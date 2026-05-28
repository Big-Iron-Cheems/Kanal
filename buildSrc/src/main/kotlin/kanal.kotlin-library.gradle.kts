plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("checkLegacyAbi")
}
