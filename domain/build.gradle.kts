plugins {
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("leyline.test-conventions")
    id("leyline.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

// Spotless is configured uniformly for all subprojects in the root build.gradle.kts.
