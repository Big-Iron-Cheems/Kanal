plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    pom {
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

publishing {
    repositories {
        maven("https://maven.meteordev.org/releases") {
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
