package leyline.testkit

import forge.ai.LobbyPlayerAi
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Seed where AI wins the coin flip and goes first. Found by probing. */
const val AI_FIRST_SEED = 2L

class MatchFlowHarnessTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("harness can start game and reach Main1 with valid accumulated state") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val acc = h.accumulator
            acc.objects.size shouldBeGreaterThan 0
            acc.actions.shouldNotBeNull()

            val missing = acc.actionInstanceIdsMissingFromObjects()
            missing.shouldBeEmpty()

            h.phase() shouldBe "MAIN1"
        }

        test("play land, pass turn, survive AI turn, reach next Main1 with valid state") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            // Play a land
            val landPlayed = h.playLand()
            landPlayed.shouldBeTrue()

            // Verify state is valid after land play
            val missingAfterLand = h.accumulator.actionInstanceIdsMissingFromObjects()
            missingAfterLand.shouldBeEmpty()

            // Pass priority to end turn
            h.passPriority()

            // The runtime continuation returns after the AI turn at the next human horizon.
            val missingAfterTurn = h.accumulator.actionInstanceIdsMissingFromObjects()
            assertSoftly {
                h.isGameOver().shouldBeFalse()
                missingAfterTurn.shouldBeEmpty()
            }
        }

        test("cast creature tracks object through zones") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            // Play land for mana
            h.playLand()

            // Cast creature (hand → stack → battlefield)
            val cast = h.castCreature()
            cast.shouldBeTrue()

            // Verify accumulated state
            val missing = h.accumulator.actionInstanceIdsMissingFromObjects()
            missing.shouldBeEmpty()

            // Verify we have objects on battlefield (not just hand/library)
            val battlefieldZone =
                h.accumulator.zones.values
                    .firstOrNull { it.type == ZoneType.Battlefield }
            battlefieldZone.shouldNotBeNull()
        }

        test("multi-turn accumulated state valid") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            repeat(3) { turn ->
                if (h.isGameOver()) return@repeat

                // Play a land if possible (OK if no land available)
                h.playLand()

                h.accumulator.assertConsistent("turn ${turn + 1}")

                // Pass turn
                h.passPriority()
            }
        }

        test("AI goes first reaches human Main1 with valid state") {
            // Verify our hardcoded seed actually has AI going first
            val probe = GameBridge(cardRepository = InMemoryCardRepository())
            probe.start(seed = AI_FIRST_SEED)
            val game = probe.getGame()!!
            val human = game.players.first { it.lobbyPlayer !is LobbyPlayerAi }
            val aiFirst = game.phaseHandler.playerTurn != human
            probe.shutdown()
            aiFirst.shouldBeTrue()

            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            assertSoftly {
                // connectAndKeep completes through the first client-owned horizon.
                h.isGameOver().shouldBeFalse()

                h.accumulator.assertConsistent("after AI-first connect")

                // Should have received at least game-start bundle (4 messages)
                h.allMessages.size shouldBeGreaterThanOrEqualTo 4
            }
        }

        test("AI-first setup passes an early human priority window") {
            val h =
                MatchFlowHarness(
                    seed = 42L,
                    deckList = "4 Giant Growth\n56 Forest",
                    engineSettings = EngineSettings(dieRollWinner = 2),
                )
            harness = h

            h.connectAndKeep()

            h.isGameOver().shouldBeFalse()
            h.phase() shouldBe "MAIN1"
            h.isAiTurn().shouldBeFalse()
        }

        test("gsId chain valid through phases") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            assertSoftly {
                h.turn() shouldBe 1
                h.phase() shouldBe "MAIN1"
                h.isAiTurn().shouldBeFalse()
            }

            // Validate chain from game start
            assertGsIdChain(h.allMessages, context = "game start")

            h.playLand()
            assertGsIdChain(h.allMessages, context = "after play land")

            h.passPriority()
            h.isGameOver().shouldBeFalse()

            // Full chain from game start through all phase transitions
            assertGsIdChain(h.allMessages, context = "after pass")
        }

        test("AI-first turn gsId chain is valid") {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            assertSoftly {
                // After connectAndKeep, the engine-owned continuation crossed the AI turn.
                h.isGameOver().shouldBeFalse()

                // Validate full gsId chain from game start
                assertGsIdChain(h.allMessages, context = "AI-first game start")

                // Validate accumulated state consistency
                h.accumulator.assertConsistent("after AI-first turn")
            }
        }

        test("AI-first multi-turn gsId chain unique") {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            // Pass through first human turn → triggers AI turn 2
            h.passPriority()
            h.isGameOver().shouldBeFalse()

            // Validate full gsId chain including 2 AI turns (turn 1 from connectAndKeep, turn 2 from pass)
            assertGsIdChain(h.allMessages, context = "AI-first 2 turns")
        }

        test("AI turn has reduced AAR count") {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            // Game-start bundle is allowed to have AAR (it's the initial prompt).
            // Grab messages after game-start, which are AI turn diffs.
            val gameStartSize =
                h.allMessages.indexOfLast {
                    it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full
                } + 1

            val aiTurnMessages = h.allMessages.subList(gameStartSize, h.allMessages.size)
            val aars = aiTurnMessages.filter { it.hasActionsAvailableReq() }

            // Before fix: every phase transition during AI turn sent AAR with pass-only
            // actions, flooding the client with "waiting for input" prompts (~6-8 AARs).
            // After fix: only combat/stack resolution paths send AAR (legitimate prompts,
            // typically 1-2). Allow up to 3 for edge cases.
            aars.size shouldBeLessThanOrEqualTo 3
        }

        test("stale PerformActionResp is rejected; fresh one is accepted") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            // After connectAndKeep we're at human MAIN1 with an AAR pending.
            // The prompt horizon should have advanced past 0.
            h.latestPromptGsId() shouldBeGreaterThan 0

            val turnBefore = h.turn()
            val phaseBefore = h.phase()
            val msgCountBefore = h.messageSnapshot()

            // Submit at horizon - 1 (the boundary case the original
            // off-by-one hotfix bumped against; we explicitly pin the
            // closed boundary "strictly less than" so a regression that
            // loosens the predicate by one is caught) and at horizon - 5
            // (a clearly-stale margin that catches a regression that
            // loosens the predicate by an arbitrary offset).
            for (delta in listOf(1, 5)) {
                val stalePass =
                    performAction { actionType = ActionType.Pass }
                        .toBuilder()
                        .setGameStateId(h.latestPromptGsId() - delta)
                        .setRespId(h.latestPromptMsgId())
                        .build()
                h.send(stalePass)
                h.drainSink()

                // Stale action ignored — no state change, no further messages.
                assertSoftly {
                    h.turn() shouldBe turnBefore
                    h.phase() shouldBe phaseBefore
                    h.messagesSince(msgCountBefore).shouldBeEmpty()
                }
            }

            // Now submit a fresh Pass at the current horizon — this time
            // the engine accepts it and advances state.
            val freshPass =
                performAction { actionType = ActionType.Pass }
                    .toBuilder()
                    .setGameStateId(h.latestPromptGsId())
                    .setRespId(h.latestPromptMsgId())
                    .build()

            h.send(freshPass)
            h.drainSink()

            // Either we changed phase/turn or the engine produced bundles
            // — anything but a no-op proves the action wasn't dropped.
            val advanced =
                h.turn() != turnBefore ||
                    h.phase() != phaseBefore ||
                    h.messagesSince(msgCountBefore).isNotEmpty()
            advanced.shouldBeTrue()
        }

        test("late PerformActionResp cannot satisfy a newer visible action window") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val oldPromptGsId = h.latestPromptGsId()
            val actionBridge = h.bridge.actionBridge(SeatId(1))
            val oldPending = actionBridge.getPending().shouldNotBeNull()
            oldPending.promptGameStateId shouldBe oldPromptGsId

            h.passPriority()
            val nextPending = actionBridge.getPending().shouldNotBeNull()
            nextPending.actionId shouldNotBe oldPending.actionId
            val nextPromptGsId = nextPending.promptGameStateId.shouldNotBeNull()
            nextPromptGsId shouldBeGreaterThan oldPromptGsId

            val latePass =
                performAction { actionType = ActionType.Pass }
                    .toBuilder()
                    .setGameStateId(oldPromptGsId)
                    .setRespId(h.latestPromptMsgId())
                    .build()
            h.send(latePass)
            h.drainSink()

            val stillPending = actionBridge.getPending().shouldNotBeNull()
            stillPending.actionId shouldBe nextPending.actionId
            stillPending.promptGameStateId shouldBe nextPromptGsId
        }

        test("AI turn produces Diff messages") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            val messagesBeforePass = h.allMessages.size

            // Play a land then pass — triggers AI turn
            h.playLand()
            h.passPriority()

            // After passing through the AI turn, we should have received Diff messages
            val newMessages = h.allMessages.subList(messagesBeforePass, h.allMessages.size)
            val diffs =
                newMessages.filter {
                    it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Diff
                }
            diffs.size shouldBeGreaterThanOrEqualTo 2
        }

        test("passUntilTurn advances through a cleanup discard") {
            // Holding more than seven cards at end of turn produces a SelectNReq
            // and no priority window. A pass loop that only knows how to Pass
            // answers nothing, and the game sits in CLEANUP until the budget
            // runs out — silently, since nothing throws.
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()
            h.human.getZone(forge.game.zone.ZoneType.Hand).size() shouldBeGreaterThan 7

            h.passUntilTurn(3)

            // Hand size is not asserted afterwards: the discard happens at the
            // end of turn 2 and the draws for turns 3 and 4 refill past seven.
            assertSoftly {
                h.isGameOver().shouldBeFalse()
                h.turn() shouldBeGreaterThanOrEqual 3
            }
        }

        test("AI turn phase annotation has details") {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            val prePassCount = h.allMessages.size
            h.passUntilTurn(3)
            h.isGameOver().shouldBeFalse()

            val aiMessages = h.allMessages.subList(prePassCount, h.allMessages.size)
            val phaseAnno =
                checkNotNull(
                    aiMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.annotationsList }
                        .firstOrNull { it.typeList.contains(AnnotationType.PhaseOrStepModified) },
                ) { "No PhaseOrStepModified annotation in AI turn messages (${aiMessages.size} post-pass msgs)" }

            val detailKeys = phaseAnno.detailsList.map { it.key }.toSet()
            assertSoftly {
                phaseAnno.affectedIdsList.shouldNotBeEmpty()
                withClue("phase annotation carried detail keys $detailKeys") {
                    setOf("phase", "step") - detailKeys shouldBe emptySet()
                }
            }
        }
    })
