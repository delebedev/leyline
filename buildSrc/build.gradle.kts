import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Pulls in the Kotlin Gradle plugin classes (PowerAssertGradleExtension, etc.)
    // so leyline.kotlin-conventions.gradle.kts can apply and configure power-assert.
    // Plugin marker GAV, resolved from the version catalog entry (DependencyHandler
    // has no direct overload for a Provider<PluginDependency>).
    implementation(
        libs.plugins.kotlin.power.assert.map {
            "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
        },
    )
}
