package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Dream Halls grants every spell an alternative cost: discard a card that
 * shares a color with it. The hand cast offer carries Dream Halls' static
 * ability row as `alternativeGrpId`.
 */
class GrantedAlternativeCostsTest :
    SessionTest({
        fun board(lands: String = "") =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Dream Halls${if (lands.isEmpty()) "" else ";$lands"}
            humanhand=Wind Drake;Raging Goblin;Opt;Shock
            humanlibrary=Island;Island;Island
            ailibrary=Forest;Forest;Forest
            """.trimIndent()

        fun MatchFlowHarness.castOffers(card: String): List<Action> {
            val iid = human.hand.iid(card)
            return allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .filter { it.actionType == ActionType.Cast && it.instanceId == iid }
        }

        fun MatchFlowHarness.dreamHallsRows(): List<Int> {
            val dreamHalls = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Dream Halls" }
            val data = bridge.cardRepository.findByGrpId(bridge.resolveGrpId(dreamHalls)) ?: return emptyList()
            return data.abilityIds.map { it.first }
        }

        session("a spell in hand is offered Dream Halls' cost when no mana is available", puzzle = board()) {
            val drake = castOffers("Wind Drake")
            val goblin = castOffers("Raging Goblin")
            assertSoftly {
                drake shouldHaveSize 1
                goblin shouldHaveSize 1
                drake.single().alternativeGrpId shouldNotBe 0
                dreamHallsRows() shouldContain drake.single().alternativeGrpId
                drake.single().manaCostList shouldBe emptyList()
                goblin.single().alternativeGrpId shouldBe drake.single().alternativeGrpId
            }
        }

        session("Dream Halls' cost discards a card sharing a color and records its row", puzzle = board()) {
            val offer = castOffers("Wind Drake").single()
            val castStart = messageSnapshot()
            submitAction(offer)
            val opt = human.hand.iid("Opt")
            allMessages.last().selectNReq.idsList shouldContain opt
            respondToSelectN(listOf(opt))
            passUntilResolved(maxPasses = 20)
            val castingTimeOption =
                messagesSince(castStart)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .single { it.typeList.contains(AnnotationType.CastingTimeOption) }
            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Wind Drake" } shouldBe true
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Opt")
                castingTimeOption.detailInt("alternateCostGrpId") shouldBe offer.alternativeGrpId
            }
        }

        session("a spell in hand keeps its mana cast beside Dream Halls' cost", puzzle = board("Island;Island;Island")) {
            val drake = castOffers("Wind Drake")
            assertSoftly {
                drake.map { it.alternativeGrpId }.sorted() shouldBe listOf(0, drake.maxOf { it.alternativeGrpId })
                drake.single { it.alternativeGrpId == 0 }.manaCostList.sumOf { it.count } shouldBe 3
            }
        }
    })
