package leyline.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.named
import leyline.game.annotations.AnnotationPipeline
import leyline.game.annotations.ConvokeContributor
import leyline.game.annotations.ManaDetailsContributor
import leyline.game.annotations.MutateMergeContributor
import leyline.game.annotations.RevealStateContributor
import leyline.game.annotations.TargetSpecContributor
import leyline.game.annotations.VehicleAttachContributor

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
            // Scope is StateMapper itself — the orchestrator the registry
            // de-accreted. Sibling mapping helpers (CardStateDesignations,
            // DayNightTransients) still do pre-existing index inserts.
            //
            // If this fails, declare ordering via a contributor rank, not a
            // manual insertion index.
            noClasses()
                .that()
                .haveNameMatching(named("leyline.game.mapping.StateMapper"))
                .should()
                .callMethodWhere(indexedListInsertion)
                .because("annotation ordering is declared through contributor rank, not an insertion index")
                .check(classes)
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

/**
 * A two-argument `add`/`addAll` whose first argument is an index — the manual
 * ordering the contributor registry replaced. Appending (`add(element)`,
 * `addAll(other)`) has a different shape and does not match.
 */
private val indexedListInsertion =
    object : DescribedPredicate<JavaMethodCall>("insert into a list at an explicit index") {
        override fun test(call: JavaMethodCall): Boolean {
            val parameters = call.target.rawParameterTypes
            return call.target.name in setOf("add", "addAll") &&
                parameters.size == 2 &&
                parameters.first().name == "int"
        }
    }
