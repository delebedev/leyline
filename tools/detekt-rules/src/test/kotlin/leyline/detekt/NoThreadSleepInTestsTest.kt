package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class NoThreadSleepInTestsTest : FunSpec({
    val rule = NoThreadSleepInTests(Config.empty)

    test("flags Thread.sleep call") {
        val code = """
            fun t() {
                Thread.sleep(100)
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags fully-qualified java.lang.Thread.sleep") {
        val code = """
            fun t() {
                java.lang.Thread.sleep(100)
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes on delay (coroutines)") {
        val code = """
            suspend fun t() {
                delay(100)
            }
            suspend fun delay(ms: Long) {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on unrelated sleep method") {
        val code = """
            object Animal { fun sleep(ms: Long) {} }
            fun t() { Animal.sleep(100) }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
