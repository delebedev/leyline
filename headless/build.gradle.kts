import leyline.build.configureTestDefaults

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    id("leyline.test-conventions")
    id("leyline.kotlin-conventions")
}

repositories {
    mavenCentral()
}

val consumerTest =
    sourceSets.create("consumerTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
    }

dependencies {
    api(project(":gre-proto"))
    implementation(project(":engine"))
    implementation(libs.kotlin.stdlib)

    add("consumerTestImplementation", project(":gre-proto"))
    add("consumerTestImplementation", libs.kotlin.stdlib)
    add("consumerTestImplementation", libs.kotlin.reflect)
    add("consumerTestImplementation", libs.kotest.runner)
    add("consumerTestImplementation", libs.kotest.assertions)
}

val testConsumer =
    tasks.register<Test>("testConsumer") {
        description = "Run headless specs compiled without engine or Forge on the consumer classpath"
        configureTestDefaults()
        testClassesDirs = consumerTest.output.classesDirs
        classpath = consumerTest.runtimeClasspath
        workingDir(rootProject.projectDir)
        maxParallelForks = 1
    }

tasks.check { dependsOn(testConsumer) }
