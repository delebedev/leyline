package leyline.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Rule
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
class EmptyAssertion(config: Config) : Rule(
    config,
    description = "Kotest test block contains no should*/assert*/fail call — likely missing its assertion.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text !in KOTEST_TEST_CALLEES) return
        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return
        val body = lambda.bodyExpression ?: return
        if (containsAssertion(body)) return
        report(
            Finding(
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
        name.startsWith("should") ||
            name.startsWith("assert") ||
            name in IMPLICIT_ASSERTIONS

    private companion object {
        // Stdlib throw-on-failure calls count as assertions in test code.
        // `error(...)` throws ISE; `check`/`require` throw when the condition is
        // false; `checkNotNull`/`requireNotNull` throw when the value is null.
        private val IMPLICIT_ASSERTIONS = setOf(
            "fail", "error", "check", "checkNotNull", "require", "requireNotNull",
        )
    }
}
