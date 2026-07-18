package leyline.build

import java.time.Duration
import org.gradle.api.tasks.testing.Test
fun Test.configureTestDefaults() {
    useJUnitPlatform()
    maxHeapSize = "1280m"
    if (project.hasProperty("forceTests")) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    if (project.findProperty("noJacoco") == "true") {
        extensions.findByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java)?.isEnabled = false
    }
    // Belt-and-suspenders: kill the whole task if Kotest itself deadlocks.
    // Kotest's per-test timeout (KotestProjectConfig = 90s) fires first in
    // the normal case; this is a last-resort for JVM-level hangs.
    timeout.set(Duration.ofMinutes(15))
    // Forward kotest filters from Gradle CLI (-P) to the test JVM
    project.findProperty("kotest.filter.specs")?.let {
        systemProperty("kotest.filter.specs", it)
    }
    project.findProperty("kotest.filter.tests")?.let {
        systemProperty("kotest.filter.tests", it)
    }
    testLogging {
        events("failed")
        if (project.hasProperty("verbose")) {
            events("failed", "passed", "skipped", "standardOut", "standardError")
            showStandardStreams = true
        }
    }
    addTestListener(CompactTestSummary())
}
