import leyline.build.CheckUpstreamTask
import leyline.build.SyncProtoTask
import leyline.build.WriteClasspathTask
import leyline.build.configureTestDefaults

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    id("org.gradle.test-retry")
    alias(libs.plugins.versions)
    jacoco
    application
}

group = "leyline"
version = "0.1.0-SNAPSHOT"

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
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.java.util)
    implementation(libs.tomlkt)
    implementation(libs.sqlite.jdbc)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec)
    implementation(libs.logback.classic)
    implementation(libs.sentry.logback)
    implementation(libs.forge.core)
    implementation(libs.forge.game)
    implementation(libs.forge.ai)
    implementation(libs.forge.gui)

    testImplementation(libs.testng)
    testImplementation(libs.archunit)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.datatest)
}

// --- Proto sync + generation ---

val syncProto by tasks.registering(SyncProtoTask::class) {
    description = "Generate messages.proto from upstream submodule + rename map"
    sedFile.set(layout.projectDirectory.file("proto/rename-map.sed"))
    upstream.set(layout.projectDirectory.file("proto/upstream/messages.proto"))
    outputFile.set(layout.projectDirectory.file("src/main/proto/messages.proto"))
}

tasks.named("extractProto") {
    dependsOn(syncProto)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

// --- Upstream JAR freshness check ---

val checkUpstream by tasks.registering(CheckUpstreamTask::class) {
    description = "Verify forge submodule JARs are installed and current"
    stampFile.set(layout.projectDirectory.file(".upstream-installed"))
    forgeDir.set(rootProject.file("forge").absolutePath)
}

tasks.named("compileKotlin") {
    dependsOn(checkUpstream)
}

// --- Code quality ---

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(
            mapOf("ktlint_standard_no-wildcard-imports" to "disabled"),
        )
    }
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

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

// --- Testing ---

tasks.test {
    configureTestDefaults()
}

val testUnit by tasks.registering(Test::class) {
    configureTestDefaults()
    useTestNG { includeGroups("unit") }
}

val testConformance by tasks.registering(Test::class) {
    configureTestDefaults()
    useTestNG { includeGroups("conformance") }
}

val testIntegration by tasks.registering(Test::class) {
    configureTestDefaults()
    useTestNG { includeGroups("integration") }
    maxParallelForks = 4
}

val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    useTestNG { includeGroups("unit", "conformance") }
}

// Kotest specs (JUnit Platform runner — coexists with TestNG tasks above)
val testKotest by tasks.registering(Test::class) {
    useJUnitPlatform()
    maxHeapSize = "768m"
    testLogging { events("failed") }
    include("**/*Test.class", "**/*Spec.class")
}

// --- JaCoCo ---

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "leyline/LeylineMain*",
                    "leyline/LeylinePaths*",
                    "leyline/analysis/**",
                    "leyline/debug/**",
                    "leyline/server/**",
                    "leyline/protocol/**",
                    "leyline/conformance/CompareMain*",
                    "leyline/conformance/GameFlowAnalyzer*",
                    "leyline/recording/**",
                    "wotc/**",
                )
            }
        }),
    )
    reports {
        xml.required.set(true)
    }
}

// --- Application ---

application {
    mainClass.set("leyline.LeylineMainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dio.netty.tryReflectionSetAccessible=true",
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    )
}

// --- Classpath file (for justfile launch helpers) ---

val writeClasspath by tasks.registering(WriteClasspathTask::class) {
    classpath.set(configurations.runtimeClasspath.map { it.asPath })
    outputFile.set(layout.projectDirectory.file("target/classpath.txt"))
}

tasks.named("classes") {
    finalizedBy(writeClasspath)
}
