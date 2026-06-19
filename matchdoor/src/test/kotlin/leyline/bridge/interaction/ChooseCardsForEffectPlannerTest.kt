package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PromptSemantic

class ChooseCardsForEffectPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("renamed host with ChooseCard TriggeredCards and ChosenCard suspect subability plans SuspectChoice") {
            val sa = suspectChoiceSa(hostName = "Different Card Name")

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeTrue()

            val plan = ChooseCardsForEffectPlanner.plan(ChooseCardsForEffectContext(sa, activeReveal = false))
            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SuspectChoice
                forcePrompt.shouldBeTrue()
                candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                mandatoryChoicePolicy shouldBe MandatoryChoicePolicy.PromptWhenSatisfied
            }
        }

        test("qualified TriggeredCards token and Suspected attribute plans SuspectChoice") {
            val sa =
                suspectChoiceSa(
                    definedCards = "TriggeredCards.Creature",
                    attributes = "Suspected",
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeTrue()
            ChooseCardsForEffectPlanner
                .plan(ChooseCardsForEffectContext(sa, activeReveal = false))
                .semantic shouldBe PromptSemantic.SuspectChoice
        }

        test("case-insensitive ChosenCard and Suspect tokens plan SuspectChoice") {
            val sa =
                chooseCardSa(
                    definedCards = "triggeredcards.artifact",
                    subAbility = alterAttributeSa(mapOf("Defined" to "chosencard", "Attributes" to "suspect")),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeTrue()
            ChooseCardsForEffectPlanner
                .plan(ChooseCardsForEffectContext(sa, activeReveal = false))
                .semantic shouldBe PromptSemantic.SuspectChoice
        }

        test("triggered ChooseCard without ChosenCard suspect subability stays generic") {
            val sa =
                chooseCardSa(
                    subAbility = alterAttributeSa(mapOf("Defined" to "ChosenCard", "Attributes" to "Flying")),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()

            val plan = ChooseCardsForEffectPlanner.plan(ChooseCardsForEffectContext(sa, activeReveal = false))
            plan.semantic shouldBe PromptSemantic.Generic
            plan.mandatoryChoicePolicy shouldBe MandatoryChoicePolicy.AutoResolveWhenSatisfied
        }

        test("non-ChooseCard suspect effect stays generic") {
            val sa = alterAttributeSa(mapOf("Defined" to "ChosenCard", "Attributes" to "Suspect"))

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()

            val plan = ChooseCardsForEffectPlanner.plan(ChooseCardsForEffectContext(sa, activeReveal = false))
            plan.semantic shouldBe PromptSemantic.Generic
            plan.forcePrompt.shouldBeFalse()
        }

        test("ChooseCard suspecting self instead of ChosenCard stays generic") {
            val sa =
                chooseCardSa(
                    subAbility = alterAttributeSa(mapOf("Defined" to "Self", "Attributes" to "Suspected")),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()
            ChooseCardsForEffectPlanner
                .plan(ChooseCardsForEffectContext(sa, activeReveal = false))
                .semantic shouldBe PromptSemantic.Generic
        }

        test("AlterAttribute deactivating Suspect on ChosenCard stays generic") {
            val sa =
                chooseCardSa(
                    subAbility =
                        alterAttributeSa(
                            mapOf("Defined" to "ChosenCard", "Attributes" to "Suspect", "Activate" to "False"),
                        ),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()
            ChooseCardsForEffectPlanner
                .plan(ChooseCardsForEffectContext(sa, activeReveal = false))
                .semantic shouldBe PromptSemantic.Generic
        }

        test("generic chooseCardsForEffect plan preserves mandatory single-choice auto-resolve") {
            val plan = ChooseCardsForEffectPlanner.plan(ChooseCardsForEffectContext(sa = null, activeReveal = false))

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.Generic
                forcePrompt shouldBe false
                candidateRefsPolicy shouldBe CandidateRefsPolicy.None
                sourceIdPolicy shouldBe SourceIdPolicy.None
                mandatoryChoicePolicy shouldBe MandatoryChoicePolicy.AutoResolveWhenSatisfied
            }
        }
    })

private fun suspectChoiceSa(
    hostName: String = "Host",
    definedCards: String = "TriggeredCards",
    attributes: String = "Suspect",
): SpellAbility =
    chooseCardSa(
        hostName = hostName,
        definedCards = definedCards,
        subAbility = alterAttributeSa(mapOf("Defined" to "ChosenCard", "Attributes" to attributes)),
    )

private fun chooseCardSa(
    hostName: String = "Host",
    definedCards: String = "TriggeredCards",
    subAbility: AbilitySub,
): SpellAbility =
    abilitySub(
        api = ApiType.ChooseCard,
        hostName = hostName,
        params = mapOf("DefinedCards" to definedCards),
    ).also { it.setSubAbility(subAbility) }

private fun alterAttributeSa(params: Map<String, String>): AbilitySub = abilitySub(api = ApiType.AlterAttribute, params = params)

private fun abilitySub(
    api: ApiType,
    hostName: String = "Host",
    params: Map<String, String> = emptyMap(),
): AbilitySub = AbilitySub(api, Card(1, null).also { it.name = hostName }, null, params)
