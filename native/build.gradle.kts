plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("leyline.test-conventions")
    id("leyline.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(project(":domain"))
    implementation(project(":engine"))
    implementation(project(":gre-proto"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.protobuf.java)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec)
    testImplementation(libs.netty.pkitesting)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.jbcrypt)
    implementation(libs.logback.classic)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":domain")))
}

tasks.test {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Spotless is configured uniformly for all subprojects in the root build.gradle.kts.
