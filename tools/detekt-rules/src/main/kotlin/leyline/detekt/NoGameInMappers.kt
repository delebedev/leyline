package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Forbids `import forge.game.Game` (and re-exports) inside GSM pipeline stages.
 *
 * The GSM pipeline reads state via [leyline.game.snapshot.GsmSnapshot]. Any
 * direct `Game` read is a temporal-coupling bug — the stage would read live
 * mutable state at a moment that earlier stages already mutated. See the
 * GsmSnapshot design spec for rationale.
 *
 * Allowed: `leyline/game/BundleBuilder.kt`, `leyline/game/snapshot/` (all files).
 * Denied: `leyline/game/mapper/` (all files), `leyline/game/StateMapper.kt`,
 * `leyline/game/AnnotationBuilder.kt`, `leyline/game/AnnotationOrderEnforcer.kt`,
 * `leyline/game/GsmBuilder.kt`.
 *
 * **`GameEventCollector` is intentionally excluded from the denied set.**
 * It is a Guava EventBus subscriber: event-handler methods fire synchronously
 * on the engine thread with live Forge objects in hand (e.g. `SpellAbilityView`,
 * live stack). Some reads (stack peek for `SpellAbility` alt-cost / mill source)
 * inherently need the live engine and cannot be served by a snapshot. Card-by-id
 * lookups have been migrated to `bridge.findCard(fid)`. The remaining
 * `bridge.getGame()` calls are scoped to stack-peek reads that are structurally
 * outside the GSM pipeline's snapshot discipline.
 */
class NoGameInMappers(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "NoGameInMappers",
        severity = Severity.Defect,
        description = "forge.game.Game is not allowed in GSM pipeline stages; " +
            "read state via leyline.game.snapshot.GsmSnapshot instead.",
        debt = Debt.TWENTY_MINS,
    )

    private val forbiddenImports = setOf(
        "forge.game.Game",
    )

    private val deniedPathFragments = listOf(
        "/leyline/game/mapper/",
        "/leyline/game/StateMapper.kt",
        "/leyline/game/AnnotationBuilder.kt",
        "/leyline/game/AnnotationOrderEnforcer.kt",
        "/leyline/game/GsmBuilder.kt",
    )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val fqName = importDirective.importedFqName?.asString() ?: return
        if (fqName !in forbiddenImports) return

        val path = importDirective.containingKtFile.virtualFilePath
        if (deniedPathFragments.none { path.contains(it) }) return

        report(
            CodeSmell(
                issue,
                Entity.from(importDirective),
                "Pipeline stage must not import $fqName. " +
                    "Read state via leyline.game.snapshot.GsmSnapshot instead.",
            ),
        )
    }
}
