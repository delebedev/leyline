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
 * Flags Kotest `test("name") { ... }` blocks whose bodies contain no
 * assertion at all — no `should*` call, no `assert*` call, no `fail(...)`.
 * Catches the common LLM mistake of writing test scaffolding without the
 * actual check. Helper calls that internally assert will mask this rule;
 * the tradeoff is accepted since the most common failure mode is inline.
 */
class EmptyAssertion(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "EmptyAssertion",
        severity = Severity.Defect,
        description = "Kotest test block contains no should*/assert*/fail call — likely missing its assertion.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "test") return
        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return
        val body = lambda.bodyExpression ?: return
        if (containsAssertion(body)) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Kotest test(\"...\") body has no should*/assert*/fail call. Add an assertion or delete the test.",
            ),
        )
    }

    private fun containsAssertion(root: KtElement): Boolean {
        var found = false
        root.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                if (!found) {
                    val name = expression.calleeExpression?.text
                    if (name != null && isAssertionName(name)) found = true
                }
                super.visitCallExpression(expression)
            }

            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                if (!found && isAssertionName(expression.operationReference.text)) found = true
                super.visitBinaryExpression(expression)
            }
        })
        return found
    }

    private fun isAssertionName(name: String): Boolean =
        name.startsWith("should") || name.startsWith("assert") || name == "fail"
}
