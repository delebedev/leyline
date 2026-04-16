package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

class BooleanAssertionTest : FunSpec({

    val rule = BooleanAssertion(Config.empty)

    test("flags (a == b).shouldBeTrue() and suggests shouldBe") {
        val code = """
            fun t() { (1 == 2).shouldBeTrue() }
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "1 shouldBe 2"
    }

    test("flags (a != 0).shouldBeTrue() and suggests shouldNotBe") {
        val code = """
            fun t() { (x != 0).shouldBeTrue() }
            val x = 1
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "x shouldNotBe 0"
    }

    test("flags (a > b).shouldBeTrue() and suggests shouldBeGreaterThan") {
        val code = """
            fun t() { (1 > 0).shouldBeTrue() }
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "1 shouldBeGreaterThan 0"
    }

    test("flags (a <= b).shouldBeTrue()") {
        val code = """
            fun t() { (a <= b).shouldBeTrue() }
            val a = 1; val b = 2
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "a shouldBeLessThanOrEqual b"
    }

    test("flags shouldBeFalse() on == and suggests shouldNotBe") {
        val code = """
            fun t() { (a == b).shouldBeFalse() }
            val a = 1; val b = 2
            fun Boolean.shouldBeFalse() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "a shouldNotBe b"
    }

    test("flags list.isEmpty().shouldBeTrue() and suggests shouldBeEmpty") {
        val code = """
            fun t() { xs.isEmpty().shouldBeTrue() }
            val xs = listOf<Int>()
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "xs.shouldBeEmpty()"
    }

    test("flags list.isNotEmpty().shouldBeFalse() and suggests shouldBeEmpty") {
        val code = """
            fun t() { xs.isNotEmpty().shouldBeFalse() }
            val xs = listOf<Int>()
            fun Boolean.shouldBeFalse() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "xs.shouldBeEmpty()"
    }

    test("flags list.contains(x).shouldBeTrue() and suggests shouldContain") {
        val code = """
            fun t() { xs.contains(42).shouldBeTrue() }
            val xs = listOf<Int>()
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "xs shouldContain 42"
    }

    test("passes on plain boolean property .shouldBeTrue()") {
        val code = """
            fun t() { flag.shouldBeTrue() }
            val flag = true
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on compound && expression (no direct matcher)") {
        val code = """
            fun t() { (a && b).shouldBeTrue() }
            val a = true; val b = true
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("unwraps nested parentheses") {
        val code = """
            fun t() { ((a == b)).shouldBeTrue() }
            val a = 1; val b = 1
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "a shouldBe b"
    }

    test("flags dotted lhs like (surveilZt.affectorId != 0).shouldBeTrue()") {
        val code = """
            fun t() { (surveilZt.affectorId != 0).shouldBeTrue() }
            class X(val affectorId: Int)
            val surveilZt = X(1)
            fun Boolean.shouldBeTrue() {}
        """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "surveilZt.affectorId shouldNotBe 0"
    }
})
