package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression

/**
 * Flags `.shouldBeTrue()` / `.shouldBeFalse()` applied to a comparison, a null
 * check, or an `isEmpty`/`isNotEmpty`/`contains` call. These wrappers collapse
 * the expression to a bare boolean before Kotest sees it, so the failure message
 * is just "expected true, got false" with no actual values — rewrite using a
 * direct matcher so failures print what the values actually were.
 */
class BooleanAssertion(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "BooleanAssertion",
        severity = Severity.Style,
        description = "shouldBeTrue()/shouldBeFalse() on a comparison hides failure detail. Use a direct Kotest matcher.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val selector = expression.selectorExpression as? KtCallExpression ?: return
        val calleeName = selector.calleeExpression?.text ?: return
        val assertTrue = when (calleeName) {
            "shouldBeTrue" -> true
            "shouldBeFalse" -> false
            else -> return
        }
        val suggestion = suggestionFor(expression.receiverExpression.unwrapParens(), assertTrue) ?: return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "$calleeName() on a comparison hides failure detail. Use: $suggestion",
            ),
        )
    }

    private fun KtExpression.unwrapParens(): KtExpression =
        if (this is KtParenthesizedExpression) (expression ?: this).unwrapParens() else this

    private fun suggestionFor(receiver: KtExpression, assertTrue: Boolean): String? {
        if (receiver is KtBinaryExpression) return binarySuggestion(receiver, assertTrue)
        if (receiver is KtDotQualifiedExpression) return collectionSuggestion(receiver, assertTrue)
        return null
    }

    private fun binarySuggestion(expr: KtBinaryExpression, assertTrue: Boolean): String? {
        val left = expr.left?.text ?: return null
        val right = expr.right?.text ?: return null
        val matcher = when (expr.operationToken) {
            KtTokens.EQEQ -> if (assertTrue) "shouldBe" else "shouldNotBe"
            KtTokens.EXCLEQ -> if (assertTrue) "shouldNotBe" else "shouldBe"
            KtTokens.GT -> if (assertTrue) "shouldBeGreaterThan" else "shouldBeLessThanOrEqual"
            KtTokens.GTEQ -> if (assertTrue) "shouldBeGreaterThanOrEqual" else "shouldBeLessThan"
            KtTokens.LT -> if (assertTrue) "shouldBeLessThan" else "shouldBeGreaterThanOrEqual"
            KtTokens.LTEQ -> if (assertTrue) "shouldBeLessThanOrEqual" else "shouldBeGreaterThan"
            else -> return null
        }
        return "$left $matcher $right"
    }

    private fun collectionSuggestion(expr: KtDotQualifiedExpression, assertTrue: Boolean): String? {
        val call = expr.selectorExpression as? KtCallExpression ?: return null
        val name = call.calleeExpression?.text ?: return null
        val target = expr.receiverExpression.text
        return when (name) {
            "isEmpty" -> if (assertTrue) "$target.shouldBeEmpty()" else "$target.shouldNotBeEmpty()"
            "isNotEmpty" -> if (assertTrue) "$target.shouldNotBeEmpty()" else "$target.shouldBeEmpty()"
            "contains" -> {
                val arg = call.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
                if (assertTrue) "$target shouldContain $arg" else "$target shouldNotContain $arg"
            }
            else -> null
        }
    }
}
