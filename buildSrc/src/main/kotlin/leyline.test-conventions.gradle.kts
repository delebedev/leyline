import leyline.build.configureTestDefaults

plugins {
    jacoco
}

tasks.named<Test>("test") {
    configureTestDefaults()
}

jacoco {
    toolVersion = "0.8.12"
}

// Don't depend on test — CI/recipes control test execution order.
// Report generates from whatever .exec files exist in build/jacoco/.
// mustRunAfter ensures the report waits for any test task that IS in the graph,
// without forcing those tasks to run when only jacocoTestReport is requested.
tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
    mustRunAfter(tasks.withType<Test>())
    // Disable build cache — exec files vary per test suite (gate vs integration)
    // but Gradle cache keys only track class files, serving stale reports.
    outputs.cacheIf { false }
}
