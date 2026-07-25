package leyline.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class MissingAssertSoftlyTest : FunSpec({

    val rule = MissingAssertSoftly(Config.empty)

    test("flags 3 consecutive infix shouldBe") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun main() {
                val x = 1; val y = 1; val z = 1
                x shouldBe 1
                y shouldBe 1
                z shouldBe 1
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            messageContains = "3 consecutive assertions",
        )
    }

    test("flags 3 consecutive .shouldBeTrue()") {
        val code = """
            fun Boolean.shouldBeTrue() {}
            fun main() {
                val a = true; val b = true; val c = true
                a.shouldBeTrue()
                b.shouldBeTrue()
                c.shouldBeTrue()
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            messageContains = "3 consecutive assertions",
        )
    }

    test("flags 4 consecutive across a mix of infix and dot forms") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun Boolean.shouldBeTrue() {}
            fun main() {
                val x = 1; val ok = true
                x shouldBe 1
                ok.shouldBeTrue()
                x shouldBe 1
                ok.shouldBeTrue()
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when assertions are inside assertSoftly") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun assertSoftly(body: () -> Unit) = body()
            fun main() {
                val x = 1; val y = 1; val z = 1
                assertSoftly {
                    x shouldBe 1
                    y shouldBe 1
                    z shouldBe 1
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on only 2 consecutive") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun main() {
                val x = 1; val y = 1
                x shouldBe 1
                y shouldBe 1
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("non-assertion statement resets the run") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun helper() {}
            fun main() {
                val x = 1; val y = 1; val z = 1
                x shouldBe 1
                y shouldBe 1
                helper()
                z shouldBe 1
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("val declarations between assertions reset the run") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun main() {
                val x = 1
                x shouldBe 1
                val y = 2
                val z = 3
                y shouldBe 2
                z shouldBe 3
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags 3 consecutive assertEquals") {
        val code = """
            fun assertEquals(a: Int, b: Int) {}
            fun main() {
                assertEquals(1, 1)
                assertEquals(2, 2)
                assertEquals(3, 3)
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("threshold of 5 via config does not flag run of 3") {
        val configured = MissingAssertSoftly(TestConfig("threshold" to 5))
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun main() {
                val x = 1; val y = 1; val z = 1
                x shouldBe 1
                y shouldBe 1
                z shouldBe 1
            }
        """.trimIndent()
        configured.lint(code).shouldBeEmpty()
    }

    test("reports twice for two separate runs in same block") {
        val code = """
            infix fun Int.shouldBe(other: Int) {}
            fun helper() {}
            fun main() {
                val x = 1; val y = 1; val z = 1
                x shouldBe 1
                y shouldBe 1
                z shouldBe 1
                helper()
                x shouldBe 1
                y shouldBe 1
                z shouldBe 1
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 2
    }
})
