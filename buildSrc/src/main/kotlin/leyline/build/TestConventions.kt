package leyline.build

import org.gradle.api.tasks.testing.Test

fun Test.configureTestDefaults() {
    useTestNG()
    maxHeapSize = "768m"
    testLogging {
        events("failed")
    }
    addTestListener(CompactTestSummary())
}
