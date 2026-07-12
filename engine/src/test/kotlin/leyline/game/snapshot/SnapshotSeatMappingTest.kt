package leyline.game.snapshot

import forge.deck.Deck
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry

class SnapshotSeatMappingTest :
    BoardTest({

        test("all-AI games preserve protocol seats in snapshots") {
            val bridge = GameBridge(cardRepository = TestCardRegistry.repo)
            useBridge(bridge)
            val game = GameBootstrap.createAiVsAiGame(Deck(), Deck())
            bridge.wrapGame(game)

            val seat1 = game.players[0]
            val seat2 = game.players[1]
            val seat1Card = addCard("Forest", seat1, ZoneType.Hand)
            val seat2Card = addCard("Mountain", seat2, ZoneType.Hand)
            game.phaseHandler.devModeSet(PhaseType.MAIN1, seat2)

            val snap = GsmSnapshot.capture(game, bridge, "test-match", 1)

            assertSoftly {
                bridge.seatOf(seat1) shouldBe SeatId(1)
                bridge.seatOf(seat2) shouldBe SeatId(2)
                snap.phase.activePlayer shouldBe SeatId(2)
                snap.objects[ForgeCardId(seat1Card.id)]?.owner shouldBe SeatId(1)
                snap.objects[ForgeCardId(seat1Card.id)]?.controller shouldBe SeatId(1)
                snap.objects[ForgeCardId(seat2Card.id)]?.owner shouldBe SeatId(2)
                snap.objects[ForgeCardId(seat2Card.id)]?.controller shouldBe SeatId(2)
            }
        }
    })
