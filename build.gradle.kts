plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
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

abstract class SyncProtoTask : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val sedFile: RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    abstract val upstream: RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputFile: RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun sync() {
        val proc = ProcessBuilder("sed", "-f", sedFile.get().asFile.absolutePath, upstream.get().asFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val result = proc.inputStream.readBytes()
        proc.waitFor()
        if (proc.exitValue() != 0) throw GradleException("sed failed: ${String(result)}")
        outputFile.get().asFile.writeBytes(result)
    }
}

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

abstract class CheckUpstreamTask : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.Optional
    abstract val stampFile: RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val forgeDir: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @org.gradle.api.tasks.TaskAction
    fun check() {
        val stamp = stampFile.orNull?.asFile
        if (stamp == null || !stamp.exists()) {
            throw GradleException("Upstream JARs not installed. Run: just install-forge")
        }
        val stampHash = stamp.readText().trim()
        val proc = ProcessBuilder("git", "log", "-1", "--format=%H", "--",
            "forge-core/src", "forge-game/src", "forge-ai/src", "forge-gui/src", "pom.xml")
            .directory(File(forgeDir.get()))
            .start()
        val upstreamHash = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (stampHash != upstreamHash) {
            throw GradleException("Upstream sources changed. Run: just install-forge")
        }
    }
}

val checkUpstream by tasks.registering(CheckUpstreamTask::class) {
    description = "Verify forge submodule JARs are installed and current"
    stampFile.set(layout.projectDirectory.file(".upstream-installed"))
    forgeDir.set(rootProject.file("forge").absolutePath)
}

tasks.named("compileKotlin") {
    dependsOn(checkUpstream)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(
            mapOf("ktlint_standard_no-wildcard-imports" to "disabled"),
        )
    }
}

// --- Power Assert (better test failure messages, zero runtime cost) ---

powerAssert {
    functions = listOf(
        "kotlin.assert",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertNull",
        "kotlin.test.assertEquals",
    )
}

// --- Detekt (static analysis) ---

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

// --- Testing ---

// Compact test summary listener — collects failures + slow tests, prints summary at end
class CompactTestSummary : TestListener {
    private val slowThreshold = 3.0
    private val failedTests = mutableListOf<Triple<String, String, String>>()
    private val slowTests = mutableListOf<Triple<String, String, Double>>()

    override fun beforeSuite(suite: TestDescriptor) {}
    override fun beforeTest(testDescriptor: TestDescriptor) {}

    override fun afterTest(desc: TestDescriptor, result: TestResult) {
        val secs = (result.endTime - result.startTime) / 1000.0
        val cls = desc.className?.substringAfterLast('.') ?: ""
        when {
            result.resultType == TestResult.ResultType.FAILURE -> {
                val msg = result.exception?.message?.lines()
                    ?.filter { it.isNotBlank() && !it.trimStart().startsWith("at ") && !it.trimStart().startsWith("...") }
                    ?.take(3)?.joinToString("\n    ")
                    ?.take(200) ?: ""
                failedTests.add(Triple(cls, desc.name, msg))
            }
            result.resultType == TestResult.ResultType.SUCCESS && secs >= slowThreshold -> {
                slowTests.add(Triple(cls, desc.name, secs))
            }
        }
    }

    override fun afterSuite(desc: TestDescriptor, result: TestResult) {
        if (desc.parent != null) return
        val total = result.testCount
        val failed = result.failedTestCount
        val skipped = result.skippedTestCount
        val passed = total - failed - skipped
        val secs = (result.endTime - result.startTime) / 1000.0
        println()
        if (failed == 0L) {
            println("PASS $passed/$passed in ${"%.1f".format(secs)}s")
            if (skipped > 0) println("  ($skipped skipped)")
        } else {
            println("FAIL $passed/$total ($failed failure${if (failed != 1L) "s" else ""}) in ${"%.1f".format(secs)}s")
            println()
            println("FAILED:")
            for ((cls, name, msg) in failedTests) {
                println("  $cls.$name")
                if (msg.isNotEmpty()) println("    $msg")
            }
        }
        if (slowTests.isNotEmpty()) {
            println()
            println("SLOW (>${"%.0f".format(slowThreshold)}s):")
            for ((cls, name, t) in slowTests.sortedByDescending { it.third }) {
                println("  $cls.$name (${"%.1f".format(t)}s)")
            }
        }
    }
}

// Shared test configuration: TestNG, heap, compact summary
fun Test.configureTestDefaults() {
    useTestNG()
    maxHeapSize = "768m"
    testLogging {
        events("failed")
    }
    addTestListener(CompactTestSummary())
}

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
    // Only discover Kotest specs — exclude TestNG classes
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
}

// --- Classpath file (for justfile launch helpers) ---

abstract class WriteClasspathTask : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val classpath: Property<String>

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputFile: RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun write() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(classpath.get())
    }
}

val writeClasspath by tasks.registering(WriteClasspathTask::class) {
    classpath.set(configurations.runtimeClasspath.map { it.asPath })
    outputFile.set(layout.projectDirectory.file("target/classpath.txt"))
}

tasks.named("classes") {
    finalizedBy(writeClasspath)
}
