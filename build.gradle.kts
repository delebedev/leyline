import leyline.build.CheckUpstreamTask
import leyline.build.WriteClasspathTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.versions)
    id("leyline.test-conventions")
    application
}

// Ktlint: applied to root + all subprojects. `.editorconfig` owns all rule config.
val ktlintVersion = "1.5.0"
apply(plugin = "org.jlleitschuh.gradle.ktlint")
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set(ktlintVersion)
}
subprojects {
    if (path == ":tools" || path == ":tools:detekt-rules") return@subprojects
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
    }
}

group = "leyline"
version = "0.1.0-SNAPSHOT"

subprojects {
    // Skip the implicit `:tools` container project and the custom-rules module
    // itself (can't depend on itself, and it doesn't need detekt's scrutiny).
    if (path == ":tools" || path == ":tools:detekt-rules") return@subprojects
    apply(plugin = "io.gitlab.arturbosch.detekt")
    repositories { mavenCentral() }
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("gradle/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        parallel = true
    }
    dependencies {
        "detektPlugins"(project(":tools:detekt-rules"))
    }
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
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Root module sources live in app/ (not default src/) so modules group visually in the tree.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("app/main/kotlin"))
        resources.setSrcDirs(listOf("app/main/resources"))
    }
    test {
        kotlin.setSrcDirs(listOf("app/test/kotlin"))
        resources.setSrcDirs(listOf("app/test/resources"))
    }
}

configurations.all {
    // Dead deps from Forge POMs — unused in headless server mode
    exclude(group = "org.eclipse.jetty")
    exclude(group = "org.eclipse.jetty.alpn")
    exclude(group = "javax.servlet")
    // Keep tinylog-api + tinylog-impl (Forge calls org.tinylog.Logger directly)
    // but exclude the SLF4J binding — we use logback for SLF4J
    exclude(group = "org.tinylog", module = "slf4j-tinylog")
}

dependencies {
    detektPlugins(project(":tools:detekt-rules"))
    implementation(project(":account"))
    implementation(project(":domain"))
    implementation(project(":engine"))
    implementation(project(":frontdoor"))
    implementation(project(":matchdoor"))
    implementation(libs.protobuf.java.util)
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec)

    implementation(libs.logback.classic)
    implementation(libs.sentry.logback)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

// --- Upstream JAR freshness check ---

val checkUpstream by tasks.registering(CheckUpstreamTask::class) {
    description = "Verify forge submodule JARs are installed and current"
    stampFile.set(layout.projectDirectory.file(".forge-commit-installed"))
    forgeDir.set(rootProject.file("forge").absolutePath)
}

tasks.named("compileKotlin") {
    dependsOn(checkUpstream)
}

// --- Code quality ---

// Ktlint: the plugin is applied to root + all subprojects above.
// All rule config lives in `.editorconfig` — no Kotlin-side overrides.

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

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("gradle/detekt.yml"))
    // The `baseline` property is the stem used by TR task baselines:
    // `detektMain` reads `gradle/detekt-baseline-main.xml` and `detektTest`
    // reads `-test.xml`. The non-TR `detekt` task would read the stem itself,
    // but that task is disabled below (redirected to the TR variants).
    baseline = file("gradle/detekt-baseline.xml")
    parallel = true
    source.setFrom(files("app/main/kotlin", "app/test/kotlin"))
}

// `./gradlew detekt` alone runs the non-TR task, which doesn't use the
// per-source-set baselines and produces misleading output. Redirect it to the
// TR variants so `just lint` and `./gradlew detekt` do the same thing.
allprojects {
    tasks.matching { it.name == "detekt" }.configureEach {
        dependsOn("detektMain", "detektTest")
        enabled = false
    }
}

// --- JaCoCo ---
// Per-module reports generated by leyline.test-conventions plugin.
// CI collects all **/jacocoTestReport.xml and ci-report.py merges them.
// Local: `just coverage` runs tests + prints summary from per-module XMLs.

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
    }
}

// --- Application ---

application {
    mainClass.set("leyline.LeylineMainKt")
    applicationDefaultJvmArgs =
        listOf(
            "-Xms384m",
            "-Xmx1g",
            "-Dio.netty.tryReflectionSetAccessible=true",
            "--add-opens",
            "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.nio=ALL-UNNAMED",
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
