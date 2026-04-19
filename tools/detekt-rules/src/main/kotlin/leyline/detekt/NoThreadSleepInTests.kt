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

/**
 * Flags `Thread.sleep(...)` in test sources. Real-time sleeps are the #1 source
 * of flaky tests — they assume work completes in X ms and fail under load or
 * on slow machines (see AiCombatAutoPassTest `elapsed < 3000` flake).
 *
 * Alternatives: Kotest `eventually { }`, `MatchFlowHarness.passUntil`, explicit
 * state polling with a terminal assertion.
 */
class NoThreadSleepInTests(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "NoThreadSleepInTests",
        severity = Severity.Defect,
        description = "Thread.sleep in tests causes flakes. Use eventually{}, passUntil, or state polling.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        // Skip production sources: Thread.sleep is legitimate in engine code
        // (GameLoopPoller, GameBridge blocking waits). Unknown paths (e.g. detekt-test
        // in-memory snippets) fall through — tests assert the rule shape directly.
        val path = expression.containingKtFile.virtualFilePath
        if ("/main/" in path || "\\main\\" in path) return
        val parent = expression.parent as? KtDotQualifiedExpression ?: return
        val callee = expression.calleeExpression?.text ?: return
        if (callee != "sleep") return
        val receiver = parent.receiverExpression.text
        if (receiver != "Thread" && receiver != "java.lang.Thread") return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Thread.sleep in tests is a flake. Use Kotest eventually{} or state polling with a terminal assertion.",
            ),
        )
    }
}
