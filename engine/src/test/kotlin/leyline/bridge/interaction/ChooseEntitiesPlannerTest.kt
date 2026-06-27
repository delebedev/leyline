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

class ChooseEntitiesPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("Escape plans graveyard exile cost semantic") {
            val plan = planFor(escapeSa())

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNCostExileFromGrave
                candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                autoReturnPolicy shouldBe AutoReturnPolicy.Prompt
                candidateRefsPolicy.candidateRefs(refs) shouldBe refs
                candidateRefsPolicy.unfilteredRefs(refs, semantic).shouldBeEmpty()
            }
        }

        test("hand to library reorder plans library putback semantic") {
            val plan = planFor(handToLibraryReorderSa())

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNLibraryPutback
                candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                candidateRefsPolicy.candidateRefs(refs) shouldBe refs
                candidateRefsPolicy.unfilteredRefs(refs, semantic).shouldBeEmpty()
            }
        }

        test("generic chooseEntities plans resolution semantic") {
            val plan = planFor(genericSa())

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNResolution
                candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                sourceIdPolicy shouldBe SourceIdPolicy.HostCard
            }
        }

        test("ChangeZone uses Search only for hidden library selections") {
            assertSoftly {
                planFor(changeZoneSa(), hiddenLibrarySelection = true).let { plan ->
                    plan.semantic shouldBe PromptSemantic.Search
                    plan.candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                    plan.candidateRefsPolicy.unfilteredRefs(refs, plan.semantic).shouldBeEmpty()
                }
                planFor(changeZoneSa(), hiddenLibrarySelection = false).let { plan ->
                    plan.semantic shouldBe PromptSemantic.SelectNResolution
                    plan.candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                    plan.candidateRefsPolicy.unfilteredRefs(refs, plan.semantic) shouldBe refs
                }
            }
        }

        test("only resolution prompts mirror candidate refs into unfiltered refs") {
            assertSoftly {
                planFor(genericSa()).let { it.candidateRefsPolicy.unfilteredRefs(refs, it.semantic) } shouldBe refs
                planFor(escapeSa()).let { it.candidateRefsPolicy.unfilteredRefs(refs, it.semantic) }.shouldBeEmpty()
                planFor(handToLibraryReorderSa()).let { it.candidateRefsPolicy.unfilteredRefs(refs, it.semantic) }.shouldBeEmpty()
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
                        hiddenLibrarySelection = false,
                    ),
                )

            assertSoftly(plan) {
                autoReturnPolicy shouldBe AutoReturnPolicy.ReturnAllWhenSelectionSatisfied
                effectiveMin shouldBe 2
                effectiveMax shouldBe 2
                candidateRefsPolicy shouldBe CandidateRefsPolicy.None
            }
        }
    })

private val refs =
    listOf(
        PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Card, entityId = 10, zone = "Hand"),
        PromptCandidateRefDto(index = 1, kind = PromptCandidateKind.Card, entityId = 11, zone = "Hand"),
        PromptCandidateRefDto(index = 2, kind = PromptCandidateKind.Card, entityId = 12, zone = "Hand"),
    )

private fun planFor(
    sa: SpellAbility,
    hiddenLibrarySelection: Boolean = false,
): ChooseEntitiesPlan =
    ChooseEntitiesPlanner.plan(
        ChooseEntitiesContext(
            sa = sa,
            min = 1,
            max = 2,
            optionCount = refs.size,
            hiddenLibrarySelection = hiddenLibrarySelection,
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

private fun changeZoneSa(): SpellAbility = abilitySub(api = ApiType.ChangeZone)

private fun abilitySub(
    api: ApiType,
    params: Map<String, String> = emptyMap(),
): AbilitySub = AbilitySub(api, Card(7, null).also { it.name = "Host" }, null, params)
