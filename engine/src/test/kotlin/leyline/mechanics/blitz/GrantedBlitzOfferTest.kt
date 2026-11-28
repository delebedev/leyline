package leyline.mechanics.blitz

import forge.game.keyword.Keyword
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.detailInt
import leyline.testkit.haveManaCost
import leyline.testkit.persistentAnnotationsOfType
import leyline.testkit.shouldHaveNoLostPlays
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Henzie "Toolbox" Torre grants Blitz to creature spells with mana value 4 or
 * greater, with the blitz cost equal to the spell's mana cost.
 */
class GrantedBlitzOfferTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Etali, Primal Conqueror;Gray Ogre
            humanbattlefield=Henzie "Toolbox" Torre;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Lightning Bolt;Mountain;Mountain;Mountain
            ailibrary=Lightning Bolt;Island;Island;Island
            """.trimIndent()

        fun MatchFlowHarness.castOffers(card: String): List<Action> {
            val iid = human.hand.iid(card)
            return allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .filter { it.actionType == ActionType.Cast && it.instanceId == iid }
        }

        session("granted Blitz is offered only to creature spells with mana value 4 or greater", puzzle = puzzle, offerDiff = true) {
            bridge.offerDiffDiagnostics.snapshot().shouldHaveNoLostPlays()
            val etali = castOffers("Etali, Primal Conqueror")
            val blitz = etali.single { it.alternativeGrpId != 0 }
            assertSoftly {
                etali.filter { it.alternativeGrpId == 0 } shouldHaveSize 1
                blitz should beAltCostOffer(blitz.alternativeGrpId)
                blitz should haveManaCost(generic = 5, red = 2)
                castOffers("Gray Ogre").map { it.alternativeGrpId } shouldBe listOf(0)
            }
        }

        session("granted Blitz casts with haste, sacrifices at end step, and draws", puzzle = puzzle) {
            val blitz = castOffers("Etali, Primal Conqueror").single { it.alternativeGrpId != 0 }
            submitAction(blitz)
            passUntil(maxPasses = 20) {
                game().stack.isEmpty && human.getZone(ZoneType.Battlefield).cards.any { it.name == "Etali, Primal Conqueror" }
            }.shouldBeTrue()

            val etali = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Etali, Primal Conqueror" }
            val cto =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .single { it.detailInt("alternateCostGrpId") == blitz.alternativeGrpId }
            assertSoftly {
                etali.hasKeyword(Keyword.HASTE) shouldBe true
                cto.detailInt("castAbilityGrpId") shouldBe blitz.alternativeGrpId
            }

            val castTurn = game().phaseHandler.turn
            val handBefore = human.getZone(ZoneType.Hand).size()
            passUntil(maxPasses = 30) {
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Etali, Primal Conqueror" } &&
                    game().stack.isEmpty
            }.shouldBeTrue()
            assertSoftly {
                game().phaseHandler.turn shouldBe castTurn
                game().phaseHandler.phase shouldBeIn listOf(PhaseType.END_OF_TURN, PhaseType.CLEANUP)
                human.getZone(ZoneType.Battlefield).cards.none { it.name == "Etali, Primal Conqueror" } shouldBe true
                human.getZone(ZoneType.Hand).size() shouldBe handBefore + 1
            }
        }

        val commanderPuzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=40
            AILife=40

            humancommand=Etali, Primal Conqueror|IsCommander
            humanbattlefield=Henzie "Toolbox" Torre;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Lightning Bolt;Mountain;Mountain;Mountain
            ailibrary=Lightning Bolt;Island;Island;Island
            """.trimIndent()

        session("a commander keeps its ordinary cast beside granted Blitz", puzzle = commanderPuzzle, offerDiff = true) {
            bridge.offerDiffDiagnostics.snapshot().shouldHaveNoLostPlays()
            val iid = instanceIdOf("Etali, Primal Conqueror", zone = ZoneType.Command)
            val etali =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.Cast && it.instanceId == iid }
            val blitz = etali.single { it.alternativeGrpId != 0 }
            assertSoftly {
                etali.filter { it.alternativeGrpId == 0 } shouldHaveSize 1
                blitz should beAltCostOffer(blitz.alternativeGrpId)
                blitz should haveManaCost(generic = 5, red = 2)
            }
        }
    })
