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
    implementation(libs.snakeyaml)
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

val ciSerialism = System.getenv("CI") == "true"
val integrationForks = (project.findProperty("integrationForks") as String?)?.toIntOrNull() ?: 1

tasks.named<Test>("test") {
    // Simclient and acceptance runs are opt-in via dedicated tasks.
    systemProperty("kotest.tags", "!SimClientTag & !AcceptanceTag")
}

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

val testIntegration by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag & !AcceptanceTag")
    maxParallelForks = integrationForks
    // Integration: MatchSession tests have their own thread pools.
    // Layering process-level forks on top flakes long-running engine flows.
}

val testAcceptance by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "AcceptanceTag")
    maxParallelForks = 1
    inputs.dir(rootProject.layout.projectDirectory.dir("puzzles"))
}

// Cache-disabled integration variant. The default `testIntegration` task is
// cacheable (`org.gradle.caching=true`) — when forge jar inputs match a prior
// run, gradle returns the cached PASS result without re-executing tests.
// Fine for iteration but masks regressions when the forge submodule pointer
// changes. `just slice-verify` calls this task; use it whenever the forge
// submodule advances.
val testIntegrationStrict by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag & !AcceptanceTag")
    maxParallelForks = integrationForks
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
}

// One-shot profiler task. Usage:
//   ./gradlew :matchdoor:profileTest --tests "leyline.session.combat.DeclareBlockersDedupeTest"
// JFR dump: /tmp/matchdoor-profile.jfr — inspect with `jfr summary` or `jfr print`.
val profileTest by tasks.registering(Test::class) {
    configureTestDefaults()
    systemProperty("kotest.tags", "IntegrationTag")
    maxParallelForks = 1
    jvmArgs("-XX:StartFlightRecording=filename=/tmp/matchdoor-profile.jfr,settings=profile,dumponexit=true")
}

val testGate by tasks.registering(Test::class) {
    configureTestDefaults()
    // Exclude SimClientTag — those are slow log-generation tests. Broad deck
    // matrices run through the standalone `:matchdoor:simclient` tool.
    systemProperty("kotest.tags", "(UnitTag | BoardTag) & !SimClientTag")
    systemProperty("kotest.framework.parallelism", (project.findProperty("kotestParallelism") as String? ?: "1"))
    // Board-tier specs share Forge process globals. Keep the gate serial by default
    // locally and in CI; use -PkotestParallelism=N only for intentional parallelism probes.
}

testIntegration.configure { mustRunAfter(testGate) }
testIntegrationStrict.configure { mustRunAfter(testGate) }

// Sim-client log generation. Drives full games via real MatchSession + bridge,
// emits scry-ts-shaped Player.log lines under build/simclient/, with sidecars
// tagging source: simclient. Tool-shaped: row failures become stats, not test
// runner failures unless --strict is passed.
//
// Configure the matrix via env vars:
//   SIMCLIENT_DECKS=mono-r-burn       (default: forest-only,bears,mono-g-curve,mono-r-burn)
//   SIMCLIENT_SEEDS=1..50             (default: 7,13,42,99,314)
val simclient by tasks.registering(JavaExec::class) {
    group = "simclient"
    description = "Run standalone simclient deck/puzzle matrices"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("leyline.tooling.simclient.SimClientToolKt")
    workingDir = rootProject.projectDir
    // Only pass through env vars that are actually set — pushing an empty
    // string clobbers the test's `?: default` fallbacks.
    val simclientKnobs =
        listOf(
            "SIMCLIENT_DECKS",
            "SIMCLIENT_OPPONENT_DECK",
            "SIMCLIENT_SEEDS",
            "SIMCLIENT_PUZZLE",
            "SIMCLIENT_POLICY",
            "SIMCLIENT_ACCEPT_OPTIONAL_COSTS",
            "SIMCLIENT_MAX_TURNS",
            "SIMCLIENT_GAME_TIMEOUT_SECONDS",
            "SIMCLIENT_EXCLUDE_CARDS",
            "SIMCLIENT_EXCLUDE_CARDS_FILE",
            "SIMCLIENT_EXCLUDE_POLICY",
            "LEYLINE_CARD_DB",
        )
    simclientKnobs.forEach { name ->
        val propertyName = name.lowercase().replace('_', '.')
        val knobValue = providers.systemProperty(propertyName).orElse(providers.environmentVariable(name)).orNull
        knobValue?.takeIf { it.isNotEmpty() }?.let { configuredValue ->
            environment(name, configuredValue)
        }
    }
    args((project.findProperty("simclientArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }.orEmpty())
    // Always re-execute — the matrix is env-driven, not source-driven, so
    // gradle's input fingerprint can't tell when we want a different run.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val simclientSmoke by tasks.registering(JavaExec::class) {
    group = "simclient"
    description = "Run a tiny standalone simclient wiring smoke"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("leyline.tooling.simclient.SimClientToolKt")
    workingDir = rootProject.projectDir
    args(
        "--decks",
        "forest-only",
        "--seeds",
        "1",
        "--max-turns",
        "2",
        "--game-timeout-seconds",
        "30",
        "--strict",
        "--out-dir",
        "${layout.buildDirectory.get().asFile}/simclient-smoke",
    )
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val simRef by tasks.registering(JavaExec::class) {
    group = "simclient"
    description = "Run Forge-AI reference callback ledger rows for simclient differential audit"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("leyline.tooling.simclient.SimRefToolKt")
    workingDir = rootProject.projectDir
    args((project.findProperty("simrefArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }.orEmpty())
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val simDiffReport by tasks.registering(JavaExec::class) {
    group = "simclient"
    description = "Join sim-ref and simclient artifacts into a differential coverage report"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("leyline.tooling.simclient.SimDiffReportToolKt")
    workingDir = rootProject.projectDir
    args((project.findProperty("simDiffReportArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }.orEmpty())
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

// Spotless is configured uniformly for all subprojects in the root build.gradle.kts.
