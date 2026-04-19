package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class NoTimingAssertsInTestsTest : FunSpec({
    val rule = NoTimingAssertsInTests(Config.empty)

    test("flags elapsed shouldBeLessThan N") {
        val code = """
            infix fun <T : Comparable<T>> T.shouldBeLessThan(other: T) = Unit
            fun t() {
                val elapsed = 3000L
                elapsed shouldBeLessThan 4000L
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags elapsed.toInt() shouldBeLessThan N") {
        val code = """
            infix fun <T : Comparable<T>> T.shouldBeLessThan(other: T) = Unit
            fun t() {
                val elapsed = 3000L
                elapsed.toInt() shouldBeLessThan 4000
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags duration shouldBeGreaterThan N") {
        val code = """
            infix fun <T : Comparable<T>> T.shouldBeGreaterThan(other: T) = Unit
            fun t() {
                val duration = 100L
                duration shouldBeGreaterThan 50L
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes on non-timing identifier") {
        val code = """
            infix fun <T : Comparable<T>> T.shouldBeLessThan(other: T) = Unit
            fun t() {
                val count = 5
                count shouldBeLessThan 10
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on shouldBe equality") {
        val code = """
            infix fun <T> T.shouldBe(other: T) = Unit
            fun t() {
                val elapsed = 3000L
                elapsed shouldBe 3000L
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
