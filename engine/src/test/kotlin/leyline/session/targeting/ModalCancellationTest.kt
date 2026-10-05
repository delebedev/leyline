package leyline.session.targeting

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ModalCancellationTest :
    SessionTest({
        session(
            "repeated-mode cancel preserves resources and permits a successful recast",
            puzzleFile = "data/puzzles/eldrazi-confluence-repeat.pzl",
        ) {
            repeat(2) {
                castSpellUntilCastingTimeOptionsReq("Eldrazi Confluence")
                // Seed the visibility cache while Forge is suspended at the modal prompt.
                game().setTopLibsCast()
                game().getTopLibForPlayer(human).shouldNotBeNull()
                cancelAction()
                assertSoftly {
                    human.getCardsIn(ZoneType.Hand).count { it.name == "Eldrazi Confluence" } shouldBe 1
                    human.getCardsIn(ZoneType.Graveyard).size shouldBe 0
                    human.getCardsIn(ZoneType.Battlefield).count { it.isTapped } shouldBe 0
                    game().stack.isEmpty.shouldBeTrue()
                    game().getTopLibForPlayer(human).shouldBeNull()
                    game().getTopLibForPlayer(ai).shouldBeNull()
                }
            }
            val modal = castSpellUntilCastingTimeOptionsReq("Eldrazi Confluence").getCastingTimeOptionReq(0).modalReq
            val token = modal.modalOptionsList.single().grpId
            respondModalChoice(listOf(token, token, token))
            passUntilResolved()
            human.getCardsIn(ZoneType.Battlefield).count { it.name == "Eldrazi Scion Token" } shouldBe 3
            playLand("Forest").shouldBeTrue()
        }
        session(
            "cancelling a discovered modal cast lets Discover finish and put the card in hand",
            puzzle =
                """
                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Hidden Courtyard;Plains;Plains;Plains;Plains;Plains
                humanhand=Forest
                humanlibrary=Eldrazi Confluence;Plains;Plains;Plains
                ailibrary=Forest;Forest;Forest;Forest
                """.trimIndent(),
        ) {
            activateAbility("Hidden Courtyard").shouldBeTrue()
            val offeredCast =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Cast
                }
            submitAction(offeredCast)
            val modal = lastCastingTimeOptionsReq().getCastingTimeOptionReq(0).modalReq
            modal.minSel shouldBe 3
            cancelAction()
            passUntilResolved()
            assertSoftly {
                human.getCardsIn(ZoneType.Hand).count { it.name == "Eldrazi Confluence" } shouldBe 1
                human.getCardsIn(ZoneType.Graveyard).map { it.name } shouldBe listOf("Hidden Courtyard")
                human.getCardsIn(ZoneType.Battlefield).count { it.isTapped } shouldBe 5
                game().stack.isEmpty.shouldBeTrue()
                bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeCastingPermission()
                    .shouldBeNull()
                game().getTopLibForPlayer(human).shouldBeNull()
            }
            playLand("Forest").shouldBeTrue()
        }
        session(
            "a permitted empty mode selection commits the cast",
            puzzle =
                """
                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                humanhand=Season of the Bold;Forest
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Forest;Forest;Forest
                """.trimIndent(),
        ) {
            val modal = castSpellUntilCastingTimeOptionsReq("Season of the Bold").getCastingTimeOptionReq(0).modalReq
            modal.minSel shouldBe 0
            respondModalChoice(emptyList())
            passUntilResolved()
            human.getCardsIn(ZoneType.Graveyard).count { it.name == "Season of the Bold" } shouldBe 1
            playLand("Forest").shouldBeTrue()
        }
    })
