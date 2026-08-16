package leyline.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.annotations.AnnotationPipeline
import leyline.game.annotations.ConvokeContributor
import leyline.game.annotations.ManaDetailsContributor
import leyline.game.annotations.MutateMergeContributor
import leyline.game.annotations.RevealStateContributor
import leyline.game.annotations.TargetSpecContributor
import leyline.game.annotations.VehicleAttachContributor
import java.nio.file.Files

/**
 * Pins the AnnotationPipeline extraction boundary so the accretion it undid
 * can't creep back into StateMapper.
 *
 * Two invariants:
 * - StateMapper constructs no annotations. Per-mechanic emitters live behind the
 *   [AnnotationPipeline.contributors] registry (or, for the effect-diff-coupled
 *   earthbend layers, as a documented spine emitter in `EarthbendEmitter.kt`).
 *   A new emitter bolted onto StateMapper would pull in [AnnotationBuilder] and
 *   trip the rule.
 * - StateMapper declares annotation ordering through contributor rank, never
 *   `annotations.add(index, …)` / `addAll(index, …)` — the manual index-based
 *   insertion that the registry replaced.
 */
class AnnotationBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses
        val sourceRoot = EngineArchitecture.sourceRoot

        test("StateMapper constructs no annotations (no per-mechanic emitter functions)") {
            noClasses()
                .that()
                .haveSimpleName("StateMapper")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("AnnotationBuilder")
                .because(
                    "per-mechanic annotation emitters live behind the AnnotationContributor registry; " +
                        "StateMapper must not grow new annotation-building functions",
                ).check(classes)
        }

        test("StateMapper declares no index-based annotation insertion") {
            // Scope is StateMapper.kt — the file the registry de-accreted. Sibling
            // mapping helpers (CardStateDesignations, DayNightTransients) still do
            // pre-existing index inserts; this guards the orchestrator, not all of
            // mapping.
            val file = sourceRoot.resolve("leyline/game/mapping/StateMapper.kt")
            // Two-arg add/addAll on an annotation list whose first arg is an
            // identifier index — the accretion pattern the registry replaced.
            // `addAll(otherList)` (append) and `add(AnnotationBuilder.x(...))`
            // (nested call) don't match this shape.
            val indexInsertion = Regex("""\.(add|addAll)\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,""")
            val violations =
                Files
                    .readAllLines(file)
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trim()
                        !trimmed.startsWith("//") &&
                            !trimmed.startsWith("*") &&
                            line.contains("annotations.") &&
                            indexInsertion.containsMatchIn(line)
                    }.map { (i, line) -> "StateMapper.kt:${i + 1}: ${line.trim()}" }

            // If this fails, declare ordering via a contributor rank, not a
            // manual insertion index.
            withClue("index-based annotation insertion reintroduced in StateMapper") {
                violations.shouldBeEmpty()
            }
        }

        test("AnnotationPipeline registry is the contributor ordering mechanism") {
            val contributors = AnnotationPipeline.contributors
            contributors.shouldNotBeEmpty()
            contributors.shouldContainAll(
                ConvokeContributor,
                RevealStateContributor,
                ManaDetailsContributor,
                TargetSpecContributor,
                MutateMergeContributor,
                VehicleAttachContributor,
            )
            // Ranks must be distinct so the documented canonical order is unambiguous.
            val ranks = contributors.map { it.rank }
            ranks.toSet().size shouldBe ranks.size
        }
    })
