package leyline.protocol

import forge.util.MyRandom
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import leyline.bridge.GameBootstrap
import leyline.game.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.DeckMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.Random

/** Integration tests for [HandshakeMessages] — die roll determinism and range. */
class HandshakeMessagesTest :
    FunSpec({

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        fun extractDieRolls(b: GameBridge, winner: Int = 2): Map<Int, Int> {
            val bundle = HandshakeMessages.initialBundle(
                seatId = 2,
                matchId = "test",
                msgIdStart = 1,
                gameStateId = 1,
                deckMessage = DeckMessage.getDefaultInstance(),
                bridge = b,
                dieRollWinner = winner,
            )
            val dieRoll = bundle.first.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.DieRollResultsResp_695e }
                .dieRollResultsResp
            return dieRoll.playerDieRollsList.associate { it.systemSeatId to it.rollValue }
        }

        test("die roll winner rolls higher") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 1L)
            repeat(10) { i ->
                MyRandom.setRandom(Random(i.toLong()))
                val rolls = extractDieRolls(b, winner = 2)
                rolls[1]!! shouldBeInRange 1..20
                rolls[2]!! shouldBeInRange 1..20
                (rolls[2]!! > rolls[1]!!) shouldBe true
            }
        }

        test("die roll deterministic with seed") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 1L)

            MyRandom.setRandom(Random(42))
            val first = extractDieRolls(b)

            MyRandom.setRandom(Random(42))
            val second = extractDieRolls(b)

            first shouldBe second
        }
    })
