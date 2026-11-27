package leyline.mechanics.mdfc

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.SessionTest
import leyline.testkit.lostPlays
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/** A modal double-faced commander offers each castable face from the command zone. */
class MdfcZoneCastTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=40
            AILife=40

            humancommand=Valki, God of Lies|IsCommander
            humanbattlefield=Swamp;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Swamp;Swamp;Swamp
            ailibrary=Island;Island;Island
            """.trimIndent()

        session(
            "a modal double-faced commander offers its front face as Cast and its back face as CastMdfc",
            puzzle = puzzle,
            offerDiff = true,
        ) {
            bridge.offerDiffDiagnostics.snapshot().lostPlays() shouldBe emptyList()
            val iid = instanceIdOf("Valki, God of Lies", zone = ZoneType.Command)
            val actions =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .filter { it.instanceId == iid }
            val casts = actions.filter { it.actionType == ActionType.Cast }
            val backFace = actions.filter { it.actionType == ActionType.CastMdfc }
            // protocol: MDFC.command-faces, MDFC.cast-instance, MDFC.no-alternative
            assertSoftly {
                casts shouldHaveSize 1
                backFace shouldHaveSize 1
                backFace.single().alternativeGrpId shouldBe 0
                backFace.single().manaCostCount shouldNotBe 0
            }
        }

        session("casting a modal double-faced commander's back face puts that face onto the battlefield", puzzle = puzzle) {
            val iid = instanceIdOf("Valki, God of Lies", zone = ZoneType.Command)
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastMdfc && it.instanceId == iid }
            submitAction(offer)
            passUntilResolved(maxPasses = 20)
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .single { it.isModal }
                .name shouldBe "Tibalt, Cosmic Impostor"
        }
    })
