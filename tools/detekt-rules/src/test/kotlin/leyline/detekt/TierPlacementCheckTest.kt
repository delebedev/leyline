package leyline.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class TierPlacementCheckTest : FunSpec({
    val rule = TierPlacementCheck(Config.empty)

    test("flags SessionTest subclass using connectAndKeep with no loop driver") {
        val code = """
            class SessionTest
            class Demo : SessionTest() {
                fun run() {
                    val h = Any()
                    h.connectAndKeep()
                }
            }
            fun Any.connectAndKeep() {}
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when passPriority present") {
        val code = """
            class SessionTest
            class Demo : SessionTest() {
                fun run() {
                    val h = Any()
                    h.connectAndKeep()
                    h.passPriority()
                }
            }
            fun Any.connectAndKeep() {}
            fun Any.passPriority() {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when onPerformAction present (prompt-pipeline test)") {
        val code = """
            class MatchFlowHarness
            class Demo {
                fun run() {
                    val h = MatchFlowHarness()
                    h.connectAndKeep()
                    h.onPerformAction()
                }
            }
            fun MatchFlowHarness.connectAndKeep() {}
            fun MatchFlowHarness.onPerformAction() {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when advanceToCombat present") {
        val code = """
            class SessionTest
            class Demo : SessionTest() {
                fun run() {
                    val h = Any()
                    h.connectAndKeep()
                    h.advanceToCombat()
                }
            }
            fun Any.connectAndKeep() {}
            fun Any.advanceToCombat() {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when no session-tier marker present") {
        val code = """
            class Demo {
                fun run() {
                    val x = 1
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
