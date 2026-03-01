package leyline.build

import org.gradle.api.tasks.testing.Test
import org.gradle.testretry.TestRetryTaskExtension

fun Test.configureTestDefaults() {
    useJUnitPlatform()
    maxHeapSize = "768m"
    testLogging {
        events("failed")
    }
    addTestListener(CompactTestSummary())
    extensions.configure(TestRetryTaskExtension::class.java) {
        maxRetries.set(2)
        maxFailures.set(3)
        failOnPassedAfterRetry.set(false)
    }
}
