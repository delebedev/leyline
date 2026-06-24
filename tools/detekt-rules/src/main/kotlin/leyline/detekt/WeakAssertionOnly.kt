package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Flags Kotest `test("name") { ... }` blocks whose only assertions are weak
 * — `shouldNotBeNull`, `shouldNotBeEmpty`, `shouldContain`, `shouldBeTrue`,
 * etc. — without a single equality-shape assertion (`shouldBe`, `shouldHaveSize`,
 * `shouldContainExactly`, `shouldBe listOf(...)`).
 *
 * Weak assertions prove something "exists or is non-empty" without proving
 * it's the *right thing*. A test that only contains them is a shape check
 * masquerading as a behaviour test: it stays green under most mutations.
 *
 * Combined with one strong assertion, weak matchers are fine (and idiomatic
 * — `val x = expr.shouldNotBeNull(); x.prop shouldBe "..."` is the sanctioned
 * pattern). The rule fires only when there's no strong assertion anywhere
 * in the test body.
 *
 * Does NOT fire when the test body is empty (that's `EmptyAssertion`'s job).
 */
class WeakAssertionOnly(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "WeakAssertionOnly",
        severity = Severity.Warning,
        description = "Test uses only weak matchers (shouldNotBeNull/Empty/Contain/BeTrue) — no equality assertion. Likely a shape check, not a behaviour test.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "test") return
        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return
        val body = lambda.bodyExpression ?: return

        val assertions = collectAssertions(body)
        if (assertions.isEmpty()) return // EmptyAssertion handles this
        if (assertions.any { it in STRONG_ASSERTIONS }) return
        if (assertions.any { it.startsWith("shouldBe") && it !in WEAK_ASSERTIONS }) {
            // shouldBeGreaterThan, shouldBeInRange etc. are OK.
            return
        }
        // Custom `assertXxx` helpers (e.g. assertConsistent, assertEquals) are
        // strong by convention; the repo uses assertSoftly as a wrapper, not
        // a standalone assertion.
        if (assertions.any { it.startsWith("assert") && it !in WRAPPER_ASSERTIONS }) return
        // All assertions are weak. Flag.
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "test body contains only weak matchers ${assertions.toSet()}. Add at least one equality-shape assertion (shouldBe, shouldHaveSize, shouldContainExactly).",
            ),
        )
    }

    private fun isAssertion(name: String): Boolean {
        // Bare "should" / "assert" are ArchUnit fluent builders, not assertions —
        // the real check happens at .check(...). Treat .check as strong.
        if (name == "should" || name == "assert") return false
        return name.startsWith("should") || name.startsWith("assert") || name == "check"
    }

    private fun collectAssertions(root: KtElement): List<String> {
        val found = mutableListOf<String>()
        root.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val name = expression.calleeExpression?.text
                if (name != null && isAssertion(name)) {
                    found += name
                }
                super.visitCallExpression(expression)
            }

            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                val op = expression.operationReference.text
                if (isKotestMatcherInfix(expression)) {
                    found += "${op}Matcher"
                } else if (isAssertion(op)) {
                    found += op
                }
                super.visitBinaryExpression(expression)
            }
        })
        return found
    }

    private fun isKotestMatcherInfix(expression: KtBinaryExpression): Boolean {
        val op = expression.operationReference.text
        if (op != "should" && op != "shouldNot") return false
        return expression.right is KtCallExpression
    }

    private companion object {
        // Weak: proves existence / truthiness / containment without pinning
        // the actual value. A test with only these passes under most mutations.
        private val WEAK_ASSERTIONS = setOf(
            "shouldNotBeNull",
            "shouldNotBeEmpty",
            "shouldContain",
            "shouldNotContain",
            "shouldContainKey",
            "shouldNotContainKey",
            "shouldBeTrue",
            "shouldBeFalse",
            "shouldBeInstanceOf",
            "shouldBeTypeOf",
            "shouldExist",
        )

        // Strong: pins exact value, size, ordered contents, or exact behaviour.
        // shouldBeNull / shouldBeEmpty: exact equality (is null / is empty collection).
        // shouldThrow*: exact behaviour (throws / doesn't throw a type).
        private val STRONG_ASSERTIONS = setOf(
            "shouldBe",
            "shouldNotBe",
            "shouldBeNull",
            "shouldBeEmpty",
            "shouldHaveSize",
            "shouldContainExactly",
            "shouldContainExactlyInAnyOrder",
            "shouldHaveSingleElement",
            "shouldMatch",
            "shouldMatchExactly",
            "shouldBeEqualComparingTo",
            "shouldBeSameInstanceAs",
            "shouldContainInOrder",
            "shouldContainOnly",
            "shouldStartWith",
            "shouldEndWith",
            "shouldThrow",
            "shouldThrowAny",
            "shouldThrowExactly",
            "shouldNotThrow",
            "shouldNotThrowAny",
            "shouldNotThrowExactly",
            "shouldMatcher",
            "shouldNotMatcher",
            // ArchUnit: .check(classes) is the evaluation step of a rule.
            // Kotlin stdlib: check(cond) / checkNotNull(x) throw on false/null.
            "check",
            "checkNotNull",
        )

        // Wrappers that don't assert themselves; their lambda body is walked.
        private val WRAPPER_ASSERTIONS = setOf("assertSoftly", "assertAll")
    }
}
