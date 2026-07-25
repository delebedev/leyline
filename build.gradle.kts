import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import dev.detekt.gradle.Detekt
import leyline.build.CheckUpstreamTask
import leyline.build.VerifyWebProfilePostureTask
import leyline.build.WriteClasspathTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

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

private val stableVersionPattern = Regex("^[0-9,.v-]+(-r)?$")

private fun String.isStableVersion(): Boolean =
    stableVersionPattern.matches(this) ||
        listOf("RELEASE", "FINAL", "GA").any { marker -> uppercase().contains(marker) }

tasks.withType<DependencyUpdatesTask>().configureEach {
    revision = "release"
    gradleReleaseChannel = "current"
    rejectVersionIf { !candidate.version.isStableVersion() }
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

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }

        val java21Launcher =
            extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
        }
        tasks.withType<Test>().configureEach {
            javaLauncher.set(java21Launcher)
        }
        tasks.withType<JavaExec>().configureEach {
            javaLauncher.set(java21Launcher)
        }
    }
}

subprojects {
    // Skip the implicit `:tools` container project and the custom-rules module
    // itself (can't depend on itself, and it doesn't need detekt's scrutiny).
    if (path == ":tools" || path == ":tools:detekt-rules") return@subprojects
    apply(plugin = "dev.detekt")
    repositories { mavenCentral() }
    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("gradle/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        parallel = true
    }
    tasks.withType<Detekt>().configureEach {
        when (name) {
            "detektMain" -> baseline.set(layout.projectDirectory.file("detekt-baseline-main.xml"))
            "detektTest" -> baseline.set(layout.projectDirectory.file("detekt-baseline-test.xml"))
        }
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

kotlin.compilerOptions {
    freeCompilerArgs.add("-Xjsr305=strict")
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
    implementation(platform(libs.netty.bom))
    implementation(project(":domain"))
    implementation(project(":engine"))
    implementation(project(":native"))
    implementation(project(":web"))
    implementation(libs.protobuf.java.util)
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec)
    implementation(libs.netty.pkitesting)
    implementation(libs.ktor.server.netty)

    implementation(libs.logback.classic)
    implementation(libs.sentry.logback)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

val webProfileRuntimeClasspath =
    configurations.create("webProfileRuntimeClasspath") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }

dependencies {
    webProfileRuntimeClasspath(sourceSets.main.get().output)
    webProfileRuntimeClasspath(project(":domain"))
    webProfileRuntimeClasspath(project(":engine"))
    webProfileRuntimeClasspath(project(":web"))
    webProfileRuntimeClasspath(libs.kotlin.stdlib)
    webProfileRuntimeClasspath(libs.serialization.json)
    webProfileRuntimeClasspath(libs.exposed.core)
    webProfileRuntimeClasspath(libs.exposed.jdbc)
    webProfileRuntimeClasspath(libs.sqlite.jdbc)
    webProfileRuntimeClasspath(libs.ktor.server.netty)
    webProfileRuntimeClasspath(libs.logback.classic)
    // logback.xml wires a Sentry appender; without this class on the
    // classpath the whole appender model fails and nothing logs at all.
    webProfileRuntimeClasspath(libs.sentry.logback)
}

// --- Upstream JAR freshness check ---

val checkUpstream =
    tasks.register<CheckUpstreamTask>("checkUpstream") {
        description = "Verify forge submodule JARs are installed and current"
        stampFile.set(layout.projectDirectory.file(".forge-commit-installed"))
        rootDir.set(rootProject.projectDir.absolutePath)
        forgeDir.set(rootProject.file("forge").absolutePath)
    }

tasks.named("compileKotlin") {
    dependsOn(checkUpstream)
}

// --- Code quality ---

// Ktlint: the plugin is applied to root + all subprojects above.
// All rule config lives in `.editorconfig` — no Kotlin-side overrides.

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

tasks.withType<Detekt>().configureEach {
    when (name) {
        "detektMain" -> baseline.set(layout.projectDirectory.file("gradle/detekt-baseline-main.xml"))
        "detektTest" -> baseline.set(layout.projectDirectory.file("gradle/detekt-baseline-test.xml"))
    }
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

val writeClasspath =
    tasks.register<WriteClasspathTask>("writeClasspath") {
        classpath.set(configurations.runtimeClasspath.map { it.asPath })
        outputFile.set(layout.projectDirectory.file("target/classpath.txt"))
    }

val writeWebProfileClasspath =
    tasks.register<WriteClasspathTask>("writeWebProfileClasspath") {
        classpath.set(providers.provider { webProfileRuntimeClasspath.asPath })
        outputFile.set(layout.projectDirectory.file("target/web-classpath.txt"))
    }

val verifyWebProfilePosture =
    tasks.register<VerifyWebProfilePostureTask>("verifyWebProfilePosture") {
        group = "verification"
        description = "Verify the web profile classpath excludes the native client head."
        dependsOn(writeWebProfileClasspath)
        classpath.from(webProfileRuntimeClasspath)
    }

tasks.named("classes") {
    finalizedBy(writeClasspath, writeWebProfileClasspath)
}
