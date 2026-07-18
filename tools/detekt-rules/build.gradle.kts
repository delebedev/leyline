plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("dev.detekt:detekt-api:${libs.versions.detekt.get()}")

    testImplementation("dev.detekt:detekt-api:${libs.versions.detekt.get()}")
    testImplementation("dev.detekt:detekt-test:${libs.versions.detekt.get()}") {
        // Alpha 5 metadata requests an unpublished detekt-api test-fixtures
        // variant. The public API dependency above supplies the runtime types.
        exclude(group = "dev.detekt", module = "detekt-api")
    }
    testImplementation("dev.detekt:detekt-test-utils:${libs.versions.detekt.get()}")
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
}
