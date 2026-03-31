package leyline.build

import org.gradle.api.tasks.testing.Test
fun Test.configureTestDefaults() {
    useJUnitPlatform()
    maxHeapSize = "1280m"
    // Forward kotest.filter.specs from Gradle CLI (-P) to the test JVM
    project.findProperty("kotest.filter.specs")?.let {
        systemProperty("kotest.filter.specs", it)
    }
    testLogging {
        events("failed")
    }
    addTestListener(CompactTestSummary())
}
