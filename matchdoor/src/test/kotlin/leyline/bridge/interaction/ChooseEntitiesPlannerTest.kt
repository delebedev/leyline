package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateRefDto

class ChooseEntitiesPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("Escape plans graveyard exile cost semantic") {
            val plan = planFor(escapeSa())

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNCostExileFromGrave
                candidateRefs shouldBe refs
                unfilteredRefs.shouldBeEmpty()
                sourceEntityId shouldBe 7
                autoReturnAll.shouldBeFalse()
            }
        }

        test("hand to library reorder plans library putback semantic") {
            val plan = planFor(handToLibraryReorderSa())

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNLibraryPutback
                candidateRefs shouldBe refs
                unfilteredRefs.shouldBeEmpty()
                sourceEntityId shouldBe 7
            }
        }

        test("generic chooseEntities plans resolution semantic") {
            val plan = planFor(genericSa())

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNResolution
                candidateRefs shouldBe refs
                sourceEntityId shouldBe 7
            }
        }

        test("only resolution prompts mirror candidate refs into unfiltered refs") {
            assertSoftly {
                planFor(genericSa()).unfilteredRefs shouldBe refs
                planFor(escapeSa()).unfilteredRefs.shouldBeEmpty()
                planFor(handToLibraryReorderSa()).unfilteredRefs.shouldBeEmpty()
            }
        }

        test("mandatory already-satisfied choices auto-return without prompt fields") {
            val plan =
                ChooseEntitiesPlanner.plan(
                    ChooseEntitiesContext(
                        sa = genericSa(),
                        min = 2,
                        max = 4,
                        optionCount = 2,
                        candidateRefs = refs,
                    ),
                )

            assertSoftly(plan) {
                autoReturnAll.shouldBeTrue()
                effectiveMin shouldBe 2
                effectiveMax shouldBe 2
                candidateRefs.shouldBeEmpty()
                unfilteredRefs.shouldBeEmpty()
            }
        }
    })

private val refs =
    listOf(
        PromptCandidateRefDto(index = 0, kind = "card", entityId = 10, zone = "Hand"),
        PromptCandidateRefDto(index = 1, kind = "card", entityId = 11, zone = "Hand"),
        PromptCandidateRefDto(index = 2, kind = "card", entityId = 12, zone = "Hand"),
    )

private fun planFor(sa: SpellAbility): ChooseEntitiesPlan =
    ChooseEntitiesPlanner.plan(
        ChooseEntitiesContext(
            sa = sa,
            min = 1,
            max = 2,
            optionCount = refs.size,
            candidateRefs = refs,
        ),
    )

private fun escapeSa(): SpellAbility = genericSa().also { it.setAlternativeCost(AlternativeCost.Escape) }

private fun handToLibraryReorderSa(): SpellAbility =
    abilitySub(
        api = ApiType.ChangeZone,
        params =
            mapOf(
                "Origin" to "Hand",
                "Destination" to "Library",
                "Reorder" to "True",
            ),
    )

private fun genericSa(): SpellAbility = abilitySub(api = ApiType.ChooseCard)

private fun abilitySub(
    api: ApiType,
    params: Map<String, String> = emptyMap(),
): AbilitySub = AbilitySub(api, Card(7, null).also { it.name = "Host" }, null, params)
