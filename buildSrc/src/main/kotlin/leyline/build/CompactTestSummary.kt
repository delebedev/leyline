package leyline.build

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

class CompactTestSummary : TestListener {
    private val slowThreshold = 3.0
    private val failedTests = mutableListOf<Triple<String, String, String>>()
    private val slowTests = mutableListOf<Triple<String, String, Double>>()

    override fun beforeSuite(suite: TestDescriptor) {}
    override fun beforeTest(testDescriptor: TestDescriptor) {}

    override fun afterTest(desc: TestDescriptor, result: TestResult) {
        val secs = (result.endTime - result.startTime) / 1000.0
        val cls = desc.className?.substringAfterLast('.') ?: ""
        when {
            result.resultType == TestResult.ResultType.FAILURE -> {
                val msg = result.exception?.message?.lines()
                    ?.filter { it.isNotBlank() && !it.trimStart().startsWith("at ") && !it.trimStart().startsWith("...") }
                    ?.take(3)?.joinToString("\n    ")
                    ?.take(200) ?: ""
                failedTests.add(Triple(cls, desc.name, msg))
            }
            result.resultType == TestResult.ResultType.SUCCESS && secs >= slowThreshold -> {
                slowTests.add(Triple(cls, desc.name, secs))
            }
        }
    }

    override fun afterSuite(desc: TestDescriptor, result: TestResult) {
        if (desc.parent != null) return
        val total = result.testCount
        val failed = result.failedTestCount
        val skipped = result.skippedTestCount
        val passed = total - failed - skipped
        val secs = (result.endTime - result.startTime) / 1000.0
        println()
        val ran = passed + failed
        if (failed == 0L) {
            println("PASS $passed/$passed in ${"%.1f".format(secs)}s")
            if (skipped > 0) println("  ($skipped skipped)")
        } else {
            println("FAIL $passed/$ran ($failed failure${if (failed != 1L) "s" else ""}) in ${"%.1f".format(secs)}s")
            if (skipped > 0) println("  ($skipped skipped)")
            println()
            println("FAILED:")
            for ((cls, name, msg) in failedTests) {
                println("  $cls.$name")
                if (msg.isNotEmpty()) println("    $msg")
            }
        }
        if (slowTests.isNotEmpty()) {
            println()
            println("SLOW (>${"%.0f".format(slowThreshold)}s):")
            for ((cls, name, t) in slowTests.sortedByDescending { it.third }) {
                println("  $cls.$name (${"%.1f".format(t)}s)")
            }
        }
    }
}
