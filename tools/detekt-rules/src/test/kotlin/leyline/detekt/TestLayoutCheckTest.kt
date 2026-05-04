package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class TestLayoutCheckTest : FunSpec({
    val rule = TestLayoutCheck(Config.empty)

    test("passes board domain with BoardTag") {
        val code = """
            package leyline.board.actions

            object BoardTag
            class FooTest {
                fun run() {
                    tags(BoardTag)
                }
            }
            fun tags(tag: Any) {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags board domain with session marker") {
        val code = """
            package leyline.board.actions

            class MatchFlowHarness
            class FooTest {
                fun run() {
                    MatchFlowHarness()
                }
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes session domain with IntegrationTag") {
        val code = """
            package leyline.session.combat

            object IntegrationTag
            class FooTest {
                fun run() {
                    tags(IntegrationTag)
                }
            }
            fun tags(tag: Any) {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags session domain with BoardTag") {
        val code = """
            package leyline.session.combat

            object BoardTag
            class FooTest {
                fun run() {
                    tags(BoardTag)
                }
            }
            fun tags(tag: Any) {}
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags mixed BoardTag and IntegrationTag in domain file") {
        val code = """
            package leyline.mechanics.flashback

            object BoardTag
            object IntegrationTag
            class FooTest {
                fun run() {
                    tags(BoardTag)
                    tags(IntegrationTag)
                }
            }
            fun tags(tag: Any) {}
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags direct mechanics package") {
        val code = """
            package leyline.mechanics

            class FlashbackTest
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes mechanic subpackage") {
        val code = """
            package leyline.mechanics.flashback

            object BoardTag
            class FlashbackActionTest {
                fun run() {
                    tags(BoardTag)
                }
            }
            fun tags(tag: Any) {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("skips conformance review bucket") {
        val code = """
            package leyline.conformance

            object BoardTag
            object IntegrationTag
            class MixedTest {
                fun run() {
                    tags(BoardTag)
                    tags(IntegrationTag)
                }
            }
            fun tags(tag: Any) {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
