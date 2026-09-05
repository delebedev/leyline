package leyline.mechanics.openinghand

import forge.game.Game
import forge.game.event.GameEventCardChangeZone
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry

class OpeningHandEventCollectorTest :
    BoardTest({
        fun assertOpeningHandAction(
            expectedSeat: SeatId,
            selectPlayer: (Game) -> Player,
        ) {
            TestCardRegistry.ensureCardRegistered("Leyline Axe")
            val (bridge, game, _) =
                startWithBoard { game, _, _ -> addCard("Leyline Axe", selectPlayer(game), ZoneType.Hand) }
            val collector = bridge.eventCollector!!
            collector.closeFrame()
            val player = selectPlayer(game)
            val card = player.getZone(ZoneType.Hand).cards.single()

            collector.visit(
                GameEventCardChangeZone(
                    card,
                    player.getZone(ZoneType.Hand),
                    player.getZone(ZoneType.Battlefield),
                ),
            )

            val action =
                collector
                    .closeFrame()
                    .events
                    .filterIsInstance<GameEvent.OpeningHandAction>()
                    .single()
            assertSoftly {
                action.cardId shouldBe ForgeCardId(card.id)
                action.seatId shouldBe expectedSeat
                action.abilityForgeId shouldBe card.id
                action.abilityGrpId shouldBe 175903
            }
        }

        test("human opening-hand battlefield put records an action") {
            assertOpeningHandAction(SeatId(1)) { it.players[0] }
        }

        test("AI opening-hand battlefield put records a seat-independent action") {
            assertOpeningHandAction(SeatId(2)) { it.players[1] }
        }

        test("hand-to-battlefield moves after game start do not record an opening-hand action") {
            TestCardRegistry.ensureCardRegistered("Leyline Axe")
            val (bridge, game, _) = startWithBoard { _, player, _ -> addCard("Leyline Axe", player, ZoneType.Hand) }
            val collector = bridge.eventCollector!!
            collector.closeFrame()
            collector.closeOpeningHandActionWindow()
            val human = game.players[0]
            val card = human.getZone(ZoneType.Hand).cards.single()

            collector.visit(
                GameEventCardChangeZone(
                    card,
                    human.getZone(ZoneType.Hand),
                    human.getZone(ZoneType.Battlefield),
                ),
            )

            collector.closeFrame().events.filterIsInstance<GameEvent.OpeningHandAction>() shouldBe emptyList()
        }
    })
