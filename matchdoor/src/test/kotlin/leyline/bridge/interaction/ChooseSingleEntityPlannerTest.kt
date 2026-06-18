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
import leyline.bridge.types.PromptCandidateRefDto

class ChooseSingleEntityPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("mutate uses special route") {
            val plan = planFor(mutateSa())

            plan.route shouldBe ChooseSingleEntityRoute.MutateTopCard
        }

        test("active reveal over card options uses special route") {
            val plan = planFor(genericSa(), activeReveal = true, allOptionsAreCards = true)

            plan.route shouldBe ChooseSingleEntityRoute.ActiveReveal
        }

        test("mandatory single option auto-returns without prompt refs") {
            val plan = planFor(genericSa(), optionCount = 1, isOptional = false)

            assertSoftly(plan) {
                route shouldBe ChooseSingleEntityRoute.AutoReturnFirst
                candidateRefs.shouldBeEmpty()
            }
        }

        test("regular prompted semantics follow spell ability shape") {
            assertSoftly {
                planFor(legendRuleSa()).semantic shouldBe PromptSemantic.SelectNLegendRule
                planFor(changeZoneSa()).semantic shouldBe PromptSemantic.Search
                planFor(genericSa(), hasDelayedReveal = true).semantic shouldBe PromptSemantic.Search
                planFor(learnSa()).semantic shouldBe PromptSemantic.LearnLesson
                planFor(genericSa()).semantic shouldBe PromptSemantic.SelectNResolution
            }
        }

        test("Learn is the only regular path that includes source id") {
            assertSoftly {
                planFor(learnSa()).sourceEntityId shouldBe 7
                planFor(legendRuleSa()).sourceEntityId shouldBe null
                planFor(changeZoneSa()).sourceEntityId shouldBe null
                planFor(genericSa()).sourceEntityId shouldBe null
            }
        }

        test("regular prompted paths include candidate refs") {
            val plan = planFor(genericSa())

            assertSoftly(plan) {
                route shouldBe ChooseSingleEntityRoute.Prompt
                candidateRefs shouldBe refs
            }
        }
    })

private val refs =
    listOf(
        PromptCandidateRefDto(index = 0, kind = "card", entityId = 10, zone = "Hand"),
        PromptCandidateRefDto(index = 1, kind = "card", entityId = 11, zone = "Hand"),
    )

private fun planFor(
    sa: SpellAbility,
    isOptional: Boolean = false,
    hasDelayedReveal: Boolean = false,
    optionCount: Int = refs.size,
    allOptionsAreCards: Boolean = true,
    activeReveal: Boolean = false,
): ChooseSingleEntityPlan =
    ChooseSingleEntityPlanner.plan(
        ChooseSingleEntityContext(
            sa = sa,
            isOptional = isOptional,
            hasDelayedReveal = hasDelayedReveal,
            optionCount = optionCount,
            allOptionsAreCards = allOptionsAreCards,
            activeReveal = activeReveal,
            candidateRefs = refs,
        ),
    )

private fun mutateSa(): SpellAbility = genericSa().also { it.setAlternativeCost(AlternativeCost.Mutate) }

private fun legendRuleSa(): SpellAbility = abilitySub(ApiType.InternalLegendaryRule)

private fun changeZoneSa(): SpellAbility = abilitySub(ApiType.ChangeZone)

private fun learnSa(): SpellAbility = abilitySub(ApiType.Learn)

private fun genericSa(): SpellAbility = abilitySub(ApiType.ChooseCard)

private fun abilitySub(api: ApiType): AbilitySub = AbilitySub(api, Card(7, null).also { it.name = "Host" }, null, emptyMap())
