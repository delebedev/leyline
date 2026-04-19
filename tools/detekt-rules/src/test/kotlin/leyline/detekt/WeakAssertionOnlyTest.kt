package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class WeakAssertionOnlyTest : FunSpec({
    val rule = WeakAssertionOnly(Config.empty)

    test("flags test with only shouldNotBeNull") {
        val code = """
            fun <T> T.shouldNotBeNull(): T = this!!
            fun test(name: String, body: () -> Unit) = body()
            val t = test("only shape") { "x".shouldNotBeNull() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags test with shouldNotBeEmpty only") {
        val code = """
            fun <T> List<T>.shouldNotBeEmpty() = this
            fun test(name: String, body: () -> Unit) = body()
            val t = test("only shape") { listOf(1).shouldNotBeEmpty() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when shouldBe present alongside weak") {
        val code = """
            fun <T> T.shouldNotBeNull(): T = this!!
            infix fun <T> T.shouldBe(other: T) = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("real check") {
                val x = "a".shouldNotBeNull()
                x shouldBe "a"
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when shouldHaveSize present") {
        val code = """
            fun <T> List<T>.shouldHaveSize(n: Int) = Unit
            fun <T> List<T>.shouldNotBeEmpty() = this
            fun test(name: String, body: () -> Unit) = body()
            val t = test("sized check") {
                val l = listOf(1, 2)
                l.shouldNotBeEmpty()
                l.shouldHaveSize(2)
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on empty body (EmptyAssertion handles)") {
        val code = """
            fun test(name: String, body: () -> Unit) = body()
            val t = test("nothing here") {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on shouldBeGreaterThan (strong, typed comparison)") {
        val code = """
            infix fun <T : Comparable<T>> T.shouldBeGreaterThan(other: T) = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("range check") { 5 shouldBeGreaterThan 3 }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags shouldBeTrue-only test") {
        val code = """
            fun Boolean.shouldBeTrue() = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("truthy only") { (1 > 0).shouldBeTrue() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }
})
