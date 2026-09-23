package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.detailInt
import leyline.testkit.exile
import leyline.testkit.graveyard
import leyline.testkit.hand
import leyline.tooling.headless.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ForgeCatalogAlternativeCostProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        timeout = 600_000L
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }
        afterEach { TestCardRegistry.repo.registeredCount shouldBe 0 }

        test("conditional alternative cost is offered and paid when its condition holds") {
            forgeCatalogProbe(
                "generic-alternative-cost",
                puzzleFile = "data/puzzles/force-of-negation-alternative-cost.pzl",
            ) { repo ->
                val forceGrpId = requireNotNull(repo.findGrpIdByName("Force of Negation"))
                val alternativeGrpId = requireNotNull(repo.findGenericAlternativeCostAbilityGrpId(forceGrpId))

                passUntil(maxPasses = 10) {
                    allMessages
                        .lastOrNull { it.hasActionsAvailableReq() }
                        ?.actionsAvailableReq
                        ?.actionsList
                        ?.any { it.actionType == ActionType.Cast && it.alternativeGrpId == alternativeGrpId } == true
                }.shouldBeTrue()
                val offer =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .single { it.actionType == ActionType.Cast && it.alternativeGrpId == alternativeGrpId }
                assertSoftly {
                    offer.grpId shouldBe forceGrpId
                    offer.manaCostList shouldBe emptyList()
                }

                val castStart = messageSnapshot()
                castSpellByName("Force of Negation", alternativeGrpId = alternativeGrpId).shouldBeTrue()
                passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                val boltIid =
                    allMessages
                        .last { it.hasSelectTargetsReq() }
                        .selectTargetsReq.targetsList
                        .flatMap { it.targetsList }
                        .map { it.targetInstanceId }
                        .single { cardByIid(it)?.name == "Lightning Bolt" }
                selectTargets(listOf(boltIid))
                passUntil(maxPasses = 5) {
                    allMessages
                        .lastOrNull { it.hasSelectTargetsReq() }
                        ?.selectTargetsReq
                        ?.targetsList
                        ?.flatMap { it.targetsList }
                        ?.any { cardByIid(it.targetInstanceId)?.name == "Opt" } == true
                }.shouldBeTrue()
                selectTargets(listOf(human.hand.iid("Opt")))
                passUntilResolved(maxPasses = 20)
                val castMessages = messagesSince(castStart)
                val castAction =
                    castMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.annotationsList }
                        .single {
                            it.typeList.contains(AnnotationType.UserActionTaken) &&
                                it.detailsList.any { detail -> detail.key == "alternativeGrpId" }
                        }
                val castingTimeOption =
                    castMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.persistentAnnotationsList }
                        .single { it.typeList.contains(AnnotationType.CastingTimeOption) }

                assertSoftly {
                    human.life shouldBe 3
                    human.exile.cards.count { it.name == "Opt" } shouldBe 1
                    ai.exile.cards.count { it.name == "Lightning Bolt" } shouldBe 1
                    human.graveyard.cards.count { it.name == "Force of Negation" } shouldBe 1
                    castAction.detailInt("alternativeGrpId") shouldBe alternativeGrpId
                    castingTimeOption.detailInt("alternateCostGrpId") shouldBe alternativeGrpId
                    castingTimeOption.detailInt("castAbilityGrpId") shouldBe alternativeGrpId
                }
            }
        }

        test("conditional alternative cost is absent when its condition does not hold") {
            forgeCatalogProbe(
                "generic-alternative-cost-condition",
                "humanhand=Force of Negation;Opt",
            ) { repo ->
                val forceGrpId = requireNotNull(repo.findGrpIdByName("Force of Negation"))
                val alternativeGrpId = requireNotNull(repo.findGenericAlternativeCostAbilityGrpId(forceGrpId))

                passUntil(maxPasses = 2) { allMessages.any { it.hasActionsAvailableReq() } }.shouldBeTrue()
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .count { it.actionType == ActionType.Cast && it.alternativeGrpId == alternativeGrpId } shouldBe 0
            }
        }
    })
