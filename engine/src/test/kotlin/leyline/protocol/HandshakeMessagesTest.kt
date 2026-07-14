package leyline.protocol

import forge.util.MyRandom
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.DeckMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.Random

/** Integration tests for [HandshakeMessages] — die roll determinism and range. */
class HandshakeMessagesTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        fun extractDieRolls(
            b: GameBridge,
            winner: Int = 2,
        ): Map<Int, Int> {
            val bundle =
                HandshakeMessages.initialBundle(
                    seatId = SeatId(2),
                    matchId = "test",
                    msgIdStart = 1,
                    gameStateId = 1,
                    deckMessage = DeckMessage.getDefaultInstance(),
                    bridge = b,
                    dieRollWinner = winner,
                )
            val dieRoll =
                bundle.first.greToClientEvent.greToClientMessagesList
                    .first { it.type == GREMessageType.DieRollResultsResp_695e }
                    .dieRollResultsResp
            return dieRoll.playerDieRollsList.associate { it.systemSeatId to it.rollValue }
        }

        test("die roll winner rolls higher") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            repeat(10) { i ->
                MyRandom.setRandom(Random(i.toLong()))
                val rolls = extractDieRolls(b, winner = 2)
                assertSoftly {
                    rolls.getValue(1) shouldBeInRange 1..20
                    rolls.getValue(2) shouldBeInRange 1..20
                    rolls.getValue(2) shouldBeGreaterThan rolls.getValue(1)
                }
            }
        }

        test("die roll deterministic with seed") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)

            MyRandom.setRandom(Random(42))
            val first = extractDieRolls(b)

            MyRandom.setRandom(Random(42))
            val second = extractDieRolls(b)

            first shouldBe second
        }

        test("initial bundle can suppress starting-player prompt for spectator seats") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 1L)
            var initialSnapshotSeen = false

            val bundle =
                HandshakeMessages.initialBundle(
                    seatId = SeatId(2),
                    matchId = "test",
                    msgIdStart = 1,
                    gameStateId = 1,
                    deckMessage = DeckMessage.getDefaultInstance(),
                    bridge = b,
                    includeStartingPlayerPrompt = false,
                    onInitialSnapshot = { initialSnapshotSeen = true },
                )

            val messages = bundle.first.greToClientEvent.greToClientMessagesList
            assertSoftly {
                messages.map { it.type } shouldBe
                    listOf(GREMessageType.DieRollResultsResp_695e, GREMessageType.GameStateMessage_695e)
                messages
                    .single { it.type == GREMessageType.GameStateMessage_695e }
                    .gameStateMessage
                    .pendingMessageCount shouldBe 0
                initialSnapshotSeen shouldBe true
            }
        }

        test("puzzle actions request carries pass-priority prompt") {
            val (message, nextMsgId) =
                HandshakeMessages.puzzleActionsReq(7, 5, SeatId(1), ActionMapper.passOnlyActions())
            val gre = message.greToClientEvent.greToClientMessagesList.single()

            assertSoftly {
                nextMsgId shouldBe 8
                gre.type shouldBe GREMessageType.ActionsAvailableReq_695e
                gre.prompt.promptId shouldBe PromptIds.PASS_PRIORITY
            }
        }
    })
