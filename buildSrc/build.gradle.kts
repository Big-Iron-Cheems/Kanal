plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.kotlin.jvm)
    implementation(libs.plugin.jmh)
    implementation(libs.plugin.dokka)
    implementation(libs.plugin.maven.publish)
}
