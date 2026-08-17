package leyline.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.annotations.AnnotationPipeline

/**
 * Pins the AnnotationPipeline boundary: StateMapper constructs no annotations.
 *
 * Per-mechanic emitters live behind the [AnnotationPipeline.contributors]
 * registry (or, for the effect-diff-coupled earthbend layers, as a documented
 * spine emitter in `EarthbendEmitter.kt`). A new emitter bolted onto StateMapper
 * would pull in [AnnotationBuilder] and trip the rule.
 *
 * Contributor ranks must stay distinct so the canonical order is unambiguous.
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

        test("contributor ranks are distinct") {
            val ranks = AnnotationPipeline.contributors.map { it.rank }
            ranks.shouldNotBeEmpty()
            withClue("duplicate contributor ranks leave the canonical order ambiguous: $ranks") {
                ranks.toSet().size shouldBe ranks.size
            }
        }
    })
