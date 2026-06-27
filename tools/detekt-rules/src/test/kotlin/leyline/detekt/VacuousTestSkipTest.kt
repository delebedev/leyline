package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

class VacuousTestSkipTest : FunSpec({

    val rule = VacuousTestSkip(Config.empty)

    test("flags !file.exists() with labeled return") {
        val code = """
            fun t() {
                listOf(1).forEach {
                    if (!file.exists()) return@forEach
                }
            }
            val file = java.io.File("x")
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            ruleId = "VacuousTestSkip",
            messageContains = "bails out silently",
        )
    }

    test("flags file.exists().not() with labeled return") {
        val code = """
            fun t() {
                listOf(1).forEach {
                    if (file.exists().not()) return@forEach
                }
            }
            val file = java.io.File("x")
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            ruleId = "VacuousTestSkip",
            messageContains = "bails out silently",
        )
    }

    test("flags nested File(path).exists() pattern") {
        val code = """
            fun t() {
                listOf(1).forEach {
                    if (!java.io.File("/tmp/a").exists()) return@forEach
                }
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            ruleId = "VacuousTestSkip",
            messageContains = "bails out silently",
        )
    }

    test("passes on unlabeled return") {
        val code = """
            fun t() {
                if (!file.exists()) return
            }
            val file = java.io.File("x")
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when the if throws instead of returning") {
        val code = """
            fun t() {
                listOf(1).forEach {
                    if (!file.exists()) error("missing")
                }
            }
            val file = java.io.File("x")
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on non-exists check with labeled return") {
        val code = """
            fun t() {
                listOf(1).forEach {
                    if (x.isEmpty()) return@forEach
                }
            }
            val x = listOf<Int>()
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
