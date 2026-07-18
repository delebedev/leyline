package leyline.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Forbids `import forge.game.Game` (and re-exports) inside GSM pipeline stages.
 *
 * The GSM pipeline reads state via [leyline.game.snapshot.GsmSnapshot]. Any
 * direct `Game` read is a temporal-coupling bug — the stage would read live
 * mutable state at a moment that earlier stages already mutated. See the
 * GsmSnapshot design spec for rationale.
 *
 * Allowed: `leyline/game/bundle/BundleBuilder.kt`, `leyline/game/snapshot/` (all files).
 * Denied: `leyline/game/mapping/` (all files), plus `AnnotationBuilder.kt`,
 * `AnnotationOrderEnforcer.kt`, and `GsmBuilder.kt` in their owning packages.
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
class NoGameInMappers(config: Config = Config.empty) : Rule(
    config,
    description = "forge.game.Game is not allowed in GSM pipeline stages; " +
            "read state via leyline.game.snapshot.GsmSnapshot instead.",
) {

    private val forbiddenImports = setOf(
        "forge.game.Game",
    )

    private val deniedFiles = setOf(
        "leyline.game.annotations" to "AnnotationBuilder.kt",
        "leyline.game.annotations" to "AnnotationOrderEnforcer.kt",
        "leyline.game.bundle" to "GsmBuilder.kt",
    )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val fqName = importDirective.importedFqName?.asString() ?: return
        if (fqName !in forbiddenImports) return

        val file = importDirective.containingKtFile
        val packageName = file.packageFqName.asString()
        val isDenied = packageName == "leyline.game.mapping" || packageName to file.name in deniedFiles
        if (!isDenied) return

        report(
            Finding(
                Entity.from(importDirective),
                "Pipeline stage must not import $fqName. " +
                    "Read state via leyline.game.snapshot.GsmSnapshot instead.",
            ),
        )
    }
}
