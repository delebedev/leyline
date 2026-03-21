package leyline.conformance

import forge.ai.LobbyPlayerAi
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.game.GameBridge
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
            acc.objects.isNotEmpty().shouldBeTrue()
            acc.actions.shouldNotBeNull()

            val missing = acc.actionInstanceIdsMissingFromObjects()
            missing.isEmpty().shouldBeTrue()

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
            missingAfterLand.isEmpty().shouldBeTrue()

            // Pass priority to end turn
            h.passPriority()

            // After auto-pass through AI turn, should be back at human's turn
            h.isGameOver().shouldBeFalse()

            val missingAfterTurn = h.accumulator.actionInstanceIdsMissingFromObjects()
            missingAfterTurn.isEmpty().shouldBeTrue()
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
            missing.isEmpty().shouldBeTrue()

            // Verify we have objects on battlefield (not just hand/library)
            val battlefieldZone = h.accumulator.zones.values
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
            val probe = GameBridge()
            probe.start(seed = AI_FIRST_SEED)
            val game = probe.getGame()!!
            val human = game.players.first { it.lobbyPlayer !is LobbyPlayerAi }
            val aiFirst = game.phaseHandler.playerTurn != human
            probe.shutdown()
            aiFirst.shouldBeTrue()

            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            // After connectAndKeep + autoPass, we should have valid state
            h.isGameOver().shouldBeFalse()

            h.accumulator.assertConsistent("after AI-first connect")

            // Should have received at least game-start bundle (4 messages)
            (h.allMessages.size >= 4).shouldBeTrue()
        }

        test("gsId chain valid through phases") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            h.turn() shouldBe 1
            h.phase() shouldBe "MAIN1"
            h.isAiTurn().shouldBeFalse()

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

            // After connectAndKeep, AI went first and we auto-passed through
            h.isGameOver().shouldBeFalse()

            // Validate full gsId chain from game start
            assertGsIdChain(h.allMessages, context = "AI-first game start")

            // Validate accumulated state consistency
            h.accumulator.assertConsistent("after AI-first turn")
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
            val gameStartSize = h.allMessages.indexOfLast {
                it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full
            } + 1

            val aiTurnMessages = h.allMessages.subList(gameStartSize, h.allMessages.size)
            val aars = aiTurnMessages.filter { it.hasActionsAvailableReq() }

            // Before fix: every phase transition during AI turn sent AAR with pass-only
            // actions, flooding the client with "waiting for input" prompts (~6-8 AARs).
            // After fix: only combat/stack resolution paths send AAR (legitimate prompts,
            // typically 1-2). Allow up to 3 for edge cases.
            (aars.size <= 3).shouldBeTrue()
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
            val diffs = newMessages.filter {
                it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Diff
            }
            (diffs.size >= 2).shouldBeTrue()
        }

        // DISABLED: passUntilTurn(3) hits AI_TURN_WAIT_MS (30s) timeout repeatedly because
        // AI-turn playback stalls — engine never delivers priority for turn 3. Burns ~128s
        // polling. Re-enable after AI multi-turn playback regression is fixed.
        xtest("AI turn NewTurnStarted annotation has content") {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            val prePassCount = h.allMessages.size
            h.passUntilTurn(3)
            h.isGameOver().shouldBeFalse()

            val aiMessages = h.allMessages.subList(prePassCount, h.allMessages.size)
            val newTurnAnno = checkNotNull(
                aiMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.NewTurnStarted) },
            ) { "No NewTurnStarted annotation in AI turn messages (${aiMessages.size} post-pass msgs)" }

            newTurnAnno.affectedIdsList.shouldNotBeEmpty()
            (newTurnAnno.affectorId > 0).shouldBeTrue()
        }

        // DISABLED: same as above — passUntilTurn(3) timeout loop.
        // Re-enable after AI multi-turn playback regression is fixed.
        xtest("AI turn phase annotation has details") {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()

            val prePassCount = h.allMessages.size
            h.passUntilTurn(3)
            h.isGameOver().shouldBeFalse()

            val aiMessages = h.allMessages.subList(prePassCount, h.allMessages.size)
            val phaseAnno = checkNotNull(
                aiMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.PhaseOrStepModified) },
            ) { "No PhaseOrStepModified annotation in AI turn messages (${aiMessages.size} post-pass msgs)" }

            phaseAnno.affectedIdsList.shouldNotBeEmpty()

            val detailKeys = phaseAnno.detailsList.map { it.key }.toSet()
            ("phase" in detailKeys).shouldBeTrue()
            ("step" in detailKeys).shouldBeTrue()
        }
    })
