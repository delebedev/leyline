package leyline.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

class BooleanAssertionTest : FunSpec({

    val rule = BooleanAssertion(Config.empty)

    fun code(expression: String) =
        """
        infix fun Boolean.shouldBe(other: Boolean) {}
        infix fun Int.shouldBe(other: Int) {}
        fun Boolean.shouldBeTrue() {}
        fun Boolean.shouldBeFalse() {}
        class X(val affectorId: Int)
        fun t() {
            val a = 1
            val b = 2
            val x = 1
            val xs = listOf<Int>()
            val flag = true
            val surveilZt = X(1)
            $expression
        }
        """.trimIndent()

    fun expectsSuggestion(
        name: String,
        expression: String,
        suggestion: String,
    ) {
        test(name) {
            rule.lint(code(expression)).shouldHaveSingleFinding(suggestion)
        }
    }

    fun passes(
        name: String,
        expression: String,
    ) {
        test(name) {
            rule.lint(code(expression)).shouldBeEmpty()
        }
    }

    expectsSuggestion(
        "flags (a == b).shouldBeTrue() and suggests shouldBe",
        "(1 == 2).shouldBeTrue()",
        "1 shouldBe 2",
    )
    expectsSuggestion(
        "flags (a != 0).shouldBeTrue() and suggests shouldNotBe",
        "(x != 0).shouldBeTrue()",
        "x shouldNotBe 0",
    )
    expectsSuggestion(
        "flags (a > b).shouldBeTrue() and suggests shouldBeGreaterThan",
        "(1 > 0).shouldBeTrue()",
        "1 shouldBeGreaterThan 0",
    )
    expectsSuggestion(
        "flags (a <= b).shouldBeTrue()",
        "(a <= b).shouldBeTrue()",
        "a shouldBeLessThanOrEqual b",
    )
    expectsSuggestion(
        "flags shouldBeFalse() on == and suggests shouldNotBe",
        "(a == b).shouldBeFalse()",
        "a shouldNotBe b",
    )
    expectsSuggestion(
        "flags list.isEmpty().shouldBeTrue() and suggests shouldBeEmpty",
        "xs.isEmpty().shouldBeTrue()",
        "xs.shouldBeEmpty()",
    )
    expectsSuggestion(
        "flags list.isNotEmpty().shouldBeFalse() and suggests shouldBeEmpty",
        "xs.isNotEmpty().shouldBeFalse()",
        "xs.shouldBeEmpty()",
    )
    expectsSuggestion(
        "flags list.contains(x).shouldBeTrue() and suggests shouldContain",
        "xs.contains(42).shouldBeTrue()",
        "xs shouldContain 42",
    )
    expectsSuggestion(
        "unwraps nested parentheses",
        "((a == b)).shouldBeTrue()",
        "a shouldBe b",
    )
    expectsSuggestion(
        "flags dotted lhs like (surveilZt.affectorId != 0).shouldBeTrue()",
        "(surveilZt.affectorId != 0).shouldBeTrue()",
        "surveilZt.affectorId shouldNotBe 0",
    )
    expectsSuggestion(
        "flags infix `(a == b) shouldBe true` and suggests shouldBe",
        "(1 == 2) shouldBe true",
        "1 shouldBe 2",
    )
    expectsSuggestion(
        "flags infix `(a != b) shouldBe true` and suggests shouldNotBe",
        "(x != 0) shouldBe true",
        "x shouldNotBe 0",
    )
    expectsSuggestion(
        "flags infix `(a > b) shouldBe true` suggests shouldBeGreaterThan",
        "(1 > 0) shouldBe true",
        "1 shouldBeGreaterThan 0",
    )
    expectsSuggestion(
        "flags infix shouldBe false on ==",
        "(a == b) shouldBe false",
        "a shouldNotBe b",
    )
    expectsSuggestion(
        "flags infix `list.isEmpty() shouldBe true` suggests shouldBeEmpty",
        "xs.isEmpty() shouldBe true",
        "xs.shouldBeEmpty()",
    )
    expectsSuggestion(
        "flags infix `list.contains(x) shouldBe true` suggests shouldContain",
        "xs.contains(42) shouldBe true",
        "xs shouldContain 42",
    )

    passes("passes on plain boolean property .shouldBeTrue()", "flag.shouldBeTrue()")
    passes("passes on compound && expression (no direct matcher)", "(flag && flag).shouldBeTrue()")
    passes("passes on `flag shouldBe true` for plain boolean var", "flag shouldBe true")
    passes("passes on `x shouldBe y` where y is not a boolean literal", "x shouldBe 1")
})
