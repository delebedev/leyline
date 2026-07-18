package leyline.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Flags labeled returns from missing-precondition checks — tests that pass
 * silently because the labeled return exits the enclosing lambda (usually a
 * Kotest test block) without running any assertion. The fix is either Kotest's
 * `config { enabledIf = ... }` to skip with intent, or an explicit
 * `io.kotest.assertions.fail(...)` so a missing precondition fails loudly.
 */
class VacuousTestSkip(config: Config) : Rule(
    config,
    description = "Labeled return based on a .exists() check bails out of the test silently. Use Kotest enabledIf or fail() explicitly.",
) {

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        val condition = expression.condition?.unwrapParens() ?: return
        if (!isSilentSkipPrecondition(condition)) return
        val thenBranch = expression.then ?: return
        val hasLabeledReturn = thenBranch.hasLabeledReturn()
        if (!hasLabeledReturn) return
        report(
            Finding(
                Entity.from(expression),
                "Test bails out silently when a precondition is missing. Use Kotest `config { enabledIf = ... }` " +
                    "to skip with intent, or call `fail(\"...\")` so a missing precondition is a loud failure.",
            ),
        )
    }

    private fun KtExpression.unwrapParens(): KtExpression =
        if (this is KtParenthesizedExpression) (expression ?: this).unwrapParens() else this

    private fun KtExpression.hasLabeledReturn(): Boolean {
        var found = false
        accept(
            object : KtTreeVisitorVoid() {
                override fun visitReturnExpression(expression: KtReturnExpression) {
                    if (expression.getLabelName() != null) found = true
                    super.visitReturnExpression(expression)
                }
            },
        )
        return found
    }

    private fun isSilentSkipPrecondition(expr: KtExpression): Boolean =
        isNegatedExistsCheck(expr) || isNullOrBlankCheck(expr)

    /**
     * Matches `!x.exists()` (prefix-not on an exists call) and `x.exists().not()`
     * (chained `.not()` on the exists result).
     */
    private fun isNegatedExistsCheck(expr: KtExpression): Boolean {
        if (expr is KtPrefixExpression && expr.operationReference.text == "!") {
            val inner = expr.baseExpression?.unwrapParens() ?: return false
            return isExistsCall(inner)
        }
        if (expr is KtDotQualifiedExpression) {
            val selector = expr.selectorExpression as? KtCallExpression ?: return false
            if (selector.calleeExpression?.text != "not") return false
            val receiver = expr.receiverExpression.unwrapParens()
            return isExistsCall(receiver)
        }
        return false
    }

    private fun isExistsCall(expr: KtExpression): Boolean {
        if (expr !is KtDotQualifiedExpression) return false
        val selector = expr.selectorExpression as? KtCallExpression ?: return false
        return selector.calleeExpression?.text == "exists"
    }

    private fun isNullOrBlankCheck(expr: KtExpression): Boolean {
        if (expr !is KtDotQualifiedExpression) return false
        val selector = expr.selectorExpression as? KtCallExpression ?: return false
        return selector.calleeExpression?.text == "isNullOrBlank"
    }
}
