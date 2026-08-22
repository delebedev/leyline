plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("leyline.test-conventions")
    id("leyline.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(project(":domain"))
    implementation(project(":engine"))
    implementation(project(":gre-proto"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.protobuf.java)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback.classic)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":domain")))
}

val generateOpenApi =
    tasks.register<JavaExec>("generateOpenApi") {
        group = "build"
        description = "Generate the web OpenAPI contract from Kotlin DTO descriptors."
        dependsOn(tasks.named("classes"))
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("leyline.web.WebOpenApiKt")
        args(
            layout.projectDirectory
                .file("src/main/resources/openapi.json")
                .asFile.absolutePath,
        )
    }
