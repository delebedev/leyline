package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Flags wall-clock timing assertions in tests:
 *   - `elapsed shouldBeLessThan 3000`
 *   - `stopwatch.elapsed.toInt() shouldBeLessThan 6000`
 *   - `duration shouldBeGreaterThan 100`
 *
 * These encode a performance expectation as a correctness assertion. They flake
 * under load, on slow CI, or when the engine takes a fractionally longer path.
 * If a perf gate is needed, use a dedicated benchmark, not FunSpec.
 *
 * Heuristic: infix `shouldBe{Less,Greater}Than[OrEqual]` where any identifier
 * in the receiver chain matches timing vocabulary (`elapsed`, `duration`, etc.).
 */
class NoTimingAssertsInTests(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "NoTimingAssertsInTests",
        severity = Severity.Defect,
        description = "Wall-clock timing asserts flake. Use a benchmark harness for perf gates.",
        debt = Debt.TEN_MINS,
    )

    private val timingNames = setOf("elapsed", "duration", "ms", "nanos", "millis", "took")
    private val ops = setOf(
        "shouldBeLessThan", "shouldBeGreaterThan",
        "shouldBeLessThanOrEqual", "shouldBeGreaterThanOrEqual",
    )

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if ("/main/" in expression.containingKtFile.virtualFilePath) return
        val op = expression.operationReference.text
        if (op !in ops) return
        val left = expression.left ?: return
        if (!containsTimingIdentifier(left)) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Wall-clock timing assert ('${left.text} $op ...') — use a benchmark harness, not a test assertion.",
            ),
        )
    }

    private fun containsTimingIdentifier(expr: KtExpression): Boolean {
        // Walk leftward through dot chains and collect identifier segments.
        val text = expr.text
        return timingNames.any { name ->
            Regex("(^|[^a-zA-Z0-9_])$name($|[^a-zA-Z0-9_])").containsMatchIn(text)
        }
    }
}
