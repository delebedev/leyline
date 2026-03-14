import leyline.build.SyncProtoTask
import leyline.build.configureTestDefaults

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.protobuf)
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
    implementation(project(":frontdoor"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    api(libs.protobuf.java)
    api(libs.protobuf.java.util) // TextFormat (ProtoDump) — api so root sees proto classes
    implementation(libs.tomlkt)             // MatchConfig TOML loading
    implementation(libs.exposed.core)       // ExposedCardRepository
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.netty.handler)      // MatchHandler, NettyMessageSink
    implementation(libs.netty.codec)        // ProtobufDecoder/Encoder
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
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

// --- Testing (base config from leyline.test-conventions) ---

val testUnit by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag")
}

val testConformance by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "ConformanceTag")
    maxParallelForks = 4
}

val testIntegration by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag")
    maxParallelForks = 4
}

val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag | ConformanceTag")
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
