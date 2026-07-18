package leyline.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Flags runs of N or more consecutive assertion statements in a block when the
 * block is not already wrapped in `assertSoftly { ... }`. The first failure
 * stops Kotest from evaluating the rest, so a multi-field check reveals only
 * one problem per run. Wrapping in `assertSoftly` collects them all, which is
 * almost always what the author wanted.
 */
class MissingAssertSoftly(config: Config) : Rule(
    config,
    description = "Consecutive should*/assert* statements should be wrapped in assertSoftly { ... } so all failures are reported.",
) {

    private val threshold: Int by config(DEFAULT_THRESHOLD)

    override fun visitBlockExpression(expression: KtBlockExpression) {
        super.visitBlockExpression(expression)
        if (expression.isInsideAssertSoftly()) return

        var runLength = 0
        var runStart: KtExpression? = null
        for (statement in expression.statements) {
            if (statement.isAssertionStatement()) {
                if (runLength == 0) runStart = statement
                runLength++
                continue
            }
            flushRun(runLength, runStart)
            runLength = 0
            runStart = null
        }
        flushRun(runLength, runStart)
    }

    private fun flushRun(length: Int, start: KtExpression?) {
        if (length < threshold || start == null) return
        report(
            Finding(
                Entity.from(start),
                "$length consecutive assertions without assertSoftly — first failure hides the rest. " +
                    "Wrap in `assertSoftly { ... }` or split into separate tests.",
            ),
        )
    }

    private fun KtExpression.isAssertionStatement(): Boolean =
        when (this) {
            is KtBinaryExpression -> isAssertionName(operationReference.text)
            is KtDotQualifiedExpression -> {
                val selector = selectorExpression
                selector is KtCallExpression &&
                    selector.calleeExpression?.text?.let(::isAssertionName) == true
            }
            is KtCallExpression ->
                calleeExpression?.text?.let(::isAssertionName) == true
            else -> false
        }

    private fun isAssertionName(name: String): Boolean =
        name.startsWith("should") || name.startsWith("assert") || name == "fail"

    private fun KtElement.isInsideAssertSoftly(): Boolean {
        var p = parent
        while (p != null) {
            if (p is KtCallExpression && p.calleeExpression?.text == "assertSoftly") return true
            p = p.parent
        }
        return false
    }

    companion object {
        private const val DEFAULT_THRESHOLD = 3
    }
}
