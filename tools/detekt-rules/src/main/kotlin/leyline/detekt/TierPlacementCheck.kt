package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClass

/**
 * Flags Session-tier tests (MatchFlowHarness / SessionTest) that never
 * drive the game loop. These pay Session cost (~0.7–3s/test) for signal
 * available at Bridge tier via `bundleBuilder(b).buildActions()` etc.
 *
 * Heuristic:
 *   - File text contains `MatchFlowHarness` or `SessionTest`
 *   - AND text contains `connectAndKeep`
 *   - AND text does NOT contain any game-loop driver:
 *       passPriority, passThroughCombat, passUntil, advanceToPhase,
 *       advanceToCombat, advanceToMain, onPerformAction,
 *       respondToOptional, respondToCast
 *
 * False positives exist (e.g. tests that intentionally assert pure post-connect
 * state). Baseline existing violations; enforce on new tests.
 */
class TierPlacementCheck(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "TierPlacementCheck",
        severity = Severity.Warning,
        description = "Session-tier test that never drives the game loop — candidate for Bridge tier (BoardTest + bundleBuilder).",
        debt = Debt.TWENTY_MINS,
    )

    private val sessionMarkers = listOf("MatchFlowHarness", "SessionTest")
    private val connectMarkers = listOf("connectAndKeep")
    private val loopDrivers = listOf(
        "passPriority",
        "passThroughCombat",
        "passUntil",
        "advanceToPhase",
        "advanceToCombat",
        "advanceToMain",
        "onPerformAction",
        "respond", // respondToOptional, respondToCast, respondModalChoice, ...
        ".session.", // direct MatchSession access — prompt/settings pipeline
        ".sink.", // engine sink inspection — pipeline-level concern
        // Wall-clock timing: test intentionally measures Session behavior,
        // e.g. AiCombatAutoPassTest — hang-detection disguised as timing assert.
        "System.currentTimeMillis",
        "System.nanoTime",
    )

    private val visitedFiles = mutableSetOf<String>()

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        val file = klass.containingKtFile
        // Report once per file to avoid duplicate warnings across nested/companion classes.
        if (!visitedFiles.add(file.virtualFilePath)) return
        val text = file.text
        if (sessionMarkers.none { it in text }) return
        if (connectMarkers.none { it in text }) return
        if (loopDrivers.any { it in text }) return
        report(
            CodeSmell(
                issue,
                Entity.from(klass),
                "Session-tier setup but no game-loop interaction — consider demoting to BoardTest + bundleBuilder(b).buildActions(). See CostReductionTest migration (commit 8e298f6).",
            ),
        )
    }
}
