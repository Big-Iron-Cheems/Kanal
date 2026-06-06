plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.kotlin.jvm)
    implementation(libs.plugin.jmh)
    implementation(libs.plugin.dokka)
    implementation(libs.plugin.maven.publish)

    // Expose generated version catalog accessors to convention plugin compilation.
    // Required for `the<LibrariesForLibs>()` to resolve inside .gradle.kts files.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
