import leyline.build.SyncProtoTask
import leyline.build.configureTestDefaults

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.protobuf)
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
    implementation(project(":frontdoor"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    api(libs.protobuf.java)
    api(libs.protobuf.java.util) // TextFormat (ProtoDump) — api so root sees proto classes
    implementation(libs.tomlkt) // MatchConfig TOML loading
    implementation(libs.exposed.core) // ExposedCardRepository
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.netty.handler) // MatchHandler, NettyMessageSink
    implementation(libs.netty.codec) // ProtobufDecoder/Encoder
    implementation(libs.logback.classic)
    api(libs.forge.core)
    api(libs.forge.game)
    api(libs.forge.ai)
    api(libs.forge.gui)

    testImplementation(libs.archunit)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.datatest)
}

// --- Proto sync + generation ---

val syncProto by tasks.registering(SyncProtoTask::class) {
    description = "Generate messages.proto from upstream submodule + rename map"
    sedFile.set(rootProject.layout.projectDirectory.file("proto/rename-map.sed"))
    upstream.set(rootProject.layout.projectDirectory.file("proto/upstream/messages.proto"))
    outputFile.set(layout.projectDirectory.file("src/main/proto/messages.proto"))
}

tasks.named("extractProto") {
    dependsOn(syncProto)
}

protobuf {
    protoc {
        artifact =
            if (System.getProperty("os.name").lowercase().contains("win") &&
                (System.getProperty("os.arch") == "aarch64" || System.getProperty("os.arch") == "arm64")
            ) {
                "com.google.protobuf:protoc:3.25.5:windows-x86_64@exe"
            } else {
                "com.google.protobuf:protoc:3.25.5"
            }
    }
}

// --- Testing (base config from leyline.test-conventions) ---

val testUnit by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag")
    systemProperty("kotest.framework.parallelism", "8")
}

val testConformance by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "ConformanceTag")
    systemProperty("kotest.framework.parallelism", "8")
}

val testIntegration by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag")
    maxParallelForks = 4
    // Integration: MatchSession tests have their own thread pools.
    // Layering Kotest parallelism on top flakes (damage/ETB/flashback).
}

// Cache-disabled integration variant. The default `testIntegration` task is
// cacheable (`org.gradle.caching=true`) — when forge jar inputs match a prior
// run, gradle returns the cached PASS result without re-executing tests.
// Fine for iteration but masks regressions when the forge submodule pointer
// changes. `just slice-verify` calls this task; use it whenever the forge
// submodule advances.
val testIntegrationStrict by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag")
    maxParallelForks = 4
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
}

// One-shot profiler task. Usage:
//   ./gradlew :matchdoor:profileTest --tests "leyline.conformance.DeclareBlockersDedupeTest"
// JFR dump: /tmp/matchdoor-profile.jfr — inspect with `jfr summary` or `jfr print`.
val profileTest by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag")
    maxParallelForks = 1
    jvmArgs("-XX:StartFlightRecording=filename=/tmp/matchdoor-profile.jfr,settings=profile,dumponexit=true")
}

val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag | ConformanceTag")
    systemProperty("kotest.framework.parallelism", (project.findProperty("kotestParallelism") as String? ?: "8"))
    // Kotest spec-level parallelism: 136 small suites, JVM-fork overhead
    // would dominate. In-JVM concurrency at 8 = ~25-27s (was ~33s serial).
    // Forge's static MyRandom race guarded by ConformanceTestBase.RNG_LOCK.
}

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

// Spotless is configured uniformly for all subprojects in the root build.gradle.kts.
