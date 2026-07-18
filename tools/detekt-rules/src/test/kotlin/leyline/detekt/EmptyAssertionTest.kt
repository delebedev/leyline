package leyline.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

class EmptyAssertionTest : FunSpec({

    val rule = EmptyAssertion(Config.empty)

    test("flags test block with zero assertions") {
        val code = """
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("empty") {
                    val x = 1 + 1
                    val y = x * 2
                }
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            messageContains = "body has no should*/assert*/fail call",
        )
    }

    test("flags test block with only setup calls") {
        val code = """
            fun test(name: String, body: () -> Unit) {}
            fun helper() {}
            fun main() {
                test("only setup") {
                    helper()
                    helper()
                }
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            messageContains = "body has no should*/assert*/fail call",
        )
    }

    test("passes when body has infix shouldBe") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("infix") {
                    val x = 1
                    x shouldBe 1
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when body has .shouldBeTrue() call") {
        val code = """
            fun Boolean.shouldBeTrue() {}
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("shouldBeTrue") {
                    val x = true
                    x.shouldBeTrue()
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when body has assertTrue") {
        val code = """
            fun assertTrue(b: Boolean) {}
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("assertTrue") {
                    assertTrue(1 > 0)
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when body has fail() call") {
        val code = """
            fun fail(msg: String): Nothing = throw RuntimeException(msg)
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("fail") {
                    if (true) fail("boom")
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when body uses error() to fail") {
        val code = """
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("error") {
                    if (true) error("boom")
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when body uses check() for assertion") {
        val code = """
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("check") {
                    val x = 1
                    check(x > 0) { "x must be positive" }
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when assertion is inside a nested block") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun assertSoftly(body: () -> Unit) = body()
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("nested") {
                    assertSoftly {
                        1 shouldBe 1
                    }
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags empty lambda body") {
        val code = """
            fun test(name: String, body: () -> Unit) {}
            fun main() {
                test("empty") { }
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            messageContains = "body has no should*/assert*/fail call",
        )
    }
})
