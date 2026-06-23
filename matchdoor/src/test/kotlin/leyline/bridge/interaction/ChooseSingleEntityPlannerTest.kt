package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class ChooseSingleEntityPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("mutate uses special route") {
            val plan = planFor(mutateSa())

            plan.routePolicy shouldBe ChooseSingleEntityRoutePolicy.MutateTopCard
        }

        test("active reveal over card options uses special route") {
            val plan = planFor(genericSa(), activeReveal = true, allOptionsAreCards = true)

            plan.routePolicy shouldBe ChooseSingleEntityRoutePolicy.ActiveReveal
        }

        test("special routes keep precedence over mandatory single-option auto-return") {
            assertSoftly {
                planFor(mutateSa(), activeReveal = true, optionCount = 1).routePolicy shouldBe
                    ChooseSingleEntityRoutePolicy.MutateTopCard
                planFor(genericSa(), activeReveal = true, optionCount = 1).routePolicy shouldBe
                    ChooseSingleEntityRoutePolicy.ActiveReveal
            }
        }

        test("mandatory single option auto-returns without prompt refs") {
            val plan = planFor(genericSa(), optionCount = 1, isOptional = false)

            assertSoftly(plan) {
                routePolicy shouldBe ChooseSingleEntityRoutePolicy.AutoReturnFirst
                candidateRefsPolicy shouldBe CandidateRefsPolicy.None
                candidateRefsPolicy.candidateRefs(refs).shouldBeEmpty()
            }
        }

        test("regular prompted semantics follow spell ability shape") {
            assertSoftly {
                planFor(legendRuleSa()).semantic shouldBe PromptSemantic.SelectNLegendRule
                planFor(changeZoneSa(), hiddenLibrarySelection = true).semantic shouldBe PromptSemantic.Search
                planFor(changeZoneSa(), hiddenLibrarySelection = false).semantic shouldBe PromptSemantic.SelectNResolution
                planFor(genericSa(), hasDelayedReveal = true).semantic shouldBe PromptSemantic.Search
                planFor(learnSa()).semantic shouldBe PromptSemantic.LearnLesson
                planFor(genericSa()).semantic shouldBe PromptSemantic.SelectNResolution
            }
        }

        test("Learn is the only regular path that includes source id") {
            assertSoftly {
                planFor(learnSa()).sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                planFor(legendRuleSa()).sourceIdPolicy shouldBe SourceIdPolicy.None
                planFor(changeZoneSa()).sourceIdPolicy shouldBe SourceIdPolicy.None
                planFor(genericSa()).sourceIdPolicy shouldBe SourceIdPolicy.None
            }
        }

        test("regular prompted paths include candidate refs") {
            val plan = planFor(genericSa())

            assertSoftly(plan) {
                routePolicy shouldBe ChooseSingleEntityRoutePolicy.Prompt
                candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                candidateRefsPolicy.candidateRefs(refs) shouldBe refs
            }
        }
    })

private val refs =
    listOf(
        PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Card, entityId = 10, zone = "Hand"),
        PromptCandidateRefDto(index = 1, kind = PromptCandidateKind.Card, entityId = 11, zone = "Hand"),
    )

private fun planFor(
    sa: SpellAbility,
    isOptional: Boolean = false,
    hasDelayedReveal: Boolean = false,
    optionCount: Int = refs.size,
    allOptionsAreCards: Boolean = true,
    hiddenLibrarySelection: Boolean = false,
    activeReveal: Boolean = false,
): ChooseSingleEntityPlan =
    ChooseSingleEntityPlanner.plan(
        ChooseSingleEntityContext(
            sa = sa,
            isOptional = isOptional,
            hasDelayedReveal = hasDelayedReveal,
            optionCount = optionCount,
            allOptionsAreCards = allOptionsAreCards,
            hiddenLibrarySelection = hiddenLibrarySelection,
            activeReveal = activeReveal,
        ),
    )

private fun mutateSa(): SpellAbility = genericSa().also { it.setAlternativeCost(AlternativeCost.Mutate) }

private fun legendRuleSa(): SpellAbility = abilitySub(ApiType.InternalLegendaryRule)

private fun changeZoneSa(): SpellAbility = abilitySub(ApiType.ChangeZone)

private fun learnSa(): SpellAbility = abilitySub(ApiType.Learn)

private fun genericSa(): SpellAbility = abilitySub(ApiType.ChooseCard)

private fun abilitySub(api: ApiType): AbilitySub = AbilitySub(api, Card(7, null).also { it.name = "Host" }, null, emptyMap())
