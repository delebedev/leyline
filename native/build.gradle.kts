import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    id("leyline.test-conventions")
}

repositories {
    mavenCentral()
    maven {
        url = uri("${rootProject.projectDir}/forge/.m2-local")
        content {
            includeGroup("forge")
        }
    }
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(project(":domain"))
    implementation(project(":engine"))
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

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions =
        listOf(
            "kotlin.assert",
            "kotlin.test.assertTrue",
            "kotlin.test.assertFalse",
            "kotlin.test.assertNull",
            "kotlin.test.assertEquals",
        )
}

tasks.test {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Spotless is configured uniformly for all subprojects in the root build.gradle.kts.
