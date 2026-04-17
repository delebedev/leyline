package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Flags `if (!x.exists()) return@label` — tests that pass silently when a
 * precondition file is missing, because the labeled return exits the enclosing
 * lambda (usually a Kotest test block) without running any assertion. The fix
 * is either Kotest's `config { enabledIf = ... }` to skip with intent, or an
 * explicit `io.kotest.assertions.fail(...)` so a missing file fails loudly.
 */
class VacuousTestSkip(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "VacuousTestSkip",
        severity = Severity.Defect,
        description = "Labeled return based on a .exists() check bails out of the test silently. Use Kotest enabledIf or fail() explicitly.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        val condition = expression.condition?.unwrapParens() ?: return
        if (!isNegatedExistsCheck(condition)) return
        val thenBranch = expression.then ?: return
        val hasLabeledReturn = thenBranch.collectDescendantsOfType<KtReturnExpression>().any {
            it.getLabelName() != null
        }
        if (!hasLabeledReturn) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Test bails out silently when the file is missing. Use Kotest `config { enabledIf = ... }` " +
                    "to skip with intent, or call `fail(\"...\")` so a missing file is a loud failure.",
            ),
        )
    }

    private fun KtExpression.unwrapParens(): KtExpression =
        if (this is KtParenthesizedExpression) (expression ?: this).unwrapParens() else this

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
}
