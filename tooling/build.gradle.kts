import leyline.build.configureTestDefaults

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.spotless)
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":matchdoor"))
    implementation(project(":frontdoor"))   // DecodeFdCapture, FdDebugCollector, SeedDb
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.logback.classic)    // DebugCollector appender
    implementation(libs.exposed.core)       // SeedDb
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.protobuf.java.util)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

// --- Testing (base config from leyline.test-conventions) ---

val testUnit by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag")
}

val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag")
}

powerAssert {
    functions = listOf(
        "kotlin.assert",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertNull",
        "kotlin.test.assertEquals",
    )
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(
            mapOf("ktlint_standard_no-wildcard-imports" to "disabled"),
        )
    }
}
