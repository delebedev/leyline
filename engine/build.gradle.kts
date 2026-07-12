import leyline.build.SyncProtoTask
import leyline.build.configureTestDefaults
import org.gradle.api.tasks.JavaExec

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

// Test-support code (headless match harness, simclient tooling) that must not
// ship in the engine jar but needs main's classes and dependencies to compile.
// Consumed by: engine tests, and the simclient/simref JavaExec tasks below.
val harness by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations["compileClasspath"]
    runtimeClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]
}

// Harness needs to see main's `internal` declarations (e.g. ActionMapper helpers),
// same as the built-in test <-> main friend relationship.
kotlin.target.compilations
    .getByName("harness")
    .associateWith(kotlin.target.compilations.getByName("main"))

sourceSets.named("test") {
    compileClasspath += harness.output
    runtimeClasspath += harness.output
}

// Test also needs harness's `internal` declarations (e.g. MatchFlowHarness.drainSink),
// same reason as the harness <-> main associate above.
kotlin.target.compilations
    .getByName("test")
    .associateWith(kotlin.target.compilations.getByName("harness"))

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    api(libs.protobuf.java)
    api(libs.protobuf.java.util)
    implementation(libs.tomlkt)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)
    implementation(libs.snakeyaml)
    api(libs.forge.core)
    api(libs.forge.game)
    api(libs.forge.ai)
    api(libs.forge.gui)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.datatest)
    testImplementation(libs.archunit)
    testImplementation(libs.kotlin.reflect)
    testImplementation(harness.output)
}

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

tasks.named<Test>("test") {
    systemProperty("kotest.tags", "!SimClientTag & !AcceptanceTag")
}

// Default fork count scales with the machine: ~1 fork per 4 cores, capped at 4.
// The CI ARM runners (8 cores) thrash at 4 forks — timing-sensitive specs miss
// their deadlines — so they land on 2; big dev machines get the full 4.
// Override with -PintegrationForks=N.
val integrationForks =
    (project.findProperty("integrationForks") as String?)?.toIntOrNull()
        ?: Runtime
            .getRuntime()
            .availableProcessors()
            .div(4)
            .coerceIn(1, 4)

val testUnit by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "UnitTag")
    systemProperty("kotest.framework.parallelism", "8")
}

val testBoard by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "BoardTag")
    systemProperty("kotest.framework.parallelism", "8")
}

val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "(UnitTag | BoardTag) & !SimClientTag")
    // Default 1: measured on the ARM CI runners, parallelism=4 made this step
    // 37-66% slower (283-345s at 1 vs 444/535s at 4) — no in-JVM parallel
    // headroom under job co-tenancy. Override with -PkotestParallelism for
    // local experiments.
    systemProperty("kotest.framework.parallelism", (project.findProperty("kotestParallelism") as String? ?: "1"))
}

val testIntegration by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag & !AcceptanceTag")
    maxParallelForks = integrationForks
}

val testIntegrationStrict by tasks.registering(Test::class) {
    configureTestDefaults()
    (project.findProperty("jfrFile") as String?)?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    systemProperty("kotest.tags", "IntegrationTag & !AcceptanceTag")
    maxParallelForks = integrationForks
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
}

val testAcceptance by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "AcceptanceTag")
    (project.findProperty("acceptanceSuites") as String?)?.let { systemProperty("acceptance.suites", it) }
    (project.findProperty("acceptanceScenarios") as String?)?.let { systemProperty("acceptance.scenarios", it) }
    maxParallelForks = 1
    inputs.dir(rootProject.layout.projectDirectory.dir("puzzles"))
}

val testSimClient by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "SimClientTag")
    systemProperty("kotest.framework.parallelism", (project.findProperty("kotestParallelism") as String? ?: "1"))
}

testIntegration.configure { mustRunAfter(testGate) }
testIntegrationStrict.configure { mustRunAfter(testGate) }

val simclient by tasks.registering(JavaExec::class) {
    group = "simclient"
    (project.findProperty("jfrFile") as String?)?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    description = "Run standalone simclient deck/puzzle matrices"
    dependsOn(tasks.named("harnessClasses"))
    classpath = sourceSets["harness"].runtimeClasspath
    mainClass.set("leyline.tooling.simclient.SimClientToolKt")
    workingDir = rootProject.projectDir
    args((project.findProperty("simclientArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }.orEmpty())
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val simref by tasks.registering(JavaExec::class) {
    group = "simclient"
    (project.findProperty("jfrFile") as String?)?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    description = "Run direct Forge AI deck/puzzle matrices"
    dependsOn(tasks.named("harnessClasses"))
    classpath = sourceSets["harness"].runtimeClasspath
    mainClass.set("leyline.tooling.simclient.SimRefToolKt")
    workingDir = rootProject.projectDir
    args((project.findProperty("simrefArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }.orEmpty())
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
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
