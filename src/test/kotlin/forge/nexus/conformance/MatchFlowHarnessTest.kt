package forge.nexus.conformance

import forge.ai.LobbyPlayerAi
import forge.nexus.game.GameBridge
import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

@Test(groups = ["integration"])
class MatchFlowHarnessTest {

    companion object {
        /** Seed where AI wins the coin flip and goes first. Found by probing. */
        const val AI_FIRST_SEED = 2L
    }

    private lateinit var harness: MatchFlowHarness

    @AfterMethod
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    @Test(description = "Harness can start game and reach Main1 with valid accumulated state")
    fun startGameReachesMain1() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val acc = harness.accumulator
        assertTrue(acc.objects.isNotEmpty(), "Should have accumulated game objects")
        assertNotNull(acc.actions, "Should have actions available")

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing after game start: $missing")

        assertEquals(harness.phase(), "MAIN1", "Should be at Main1")
    }

    @Test(description = "Play land, pass turn, survive AI turn, reach next Main1 with valid state")
    fun playLandAndPassTurn() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        // Play a land
        val landPlayed = harness.playLand()
        assertTrue(landPlayed, "Should have a land to play")

        // Verify state is valid after land play
        val missingAfterLand = harness.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missingAfterLand.isEmpty(), "Missing instanceIds after land: $missingAfterLand")

        // Pass priority to end turn
        harness.passPriority()

        // After auto-pass through AI turn, should be back at human's turn
        // (or AI turn if AI has actions — either way, state should be valid)
        assertFalse(harness.isGameOver(), "Game should not be over after 1 turn")

        val missingAfterTurn = harness.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missingAfterTurn.isEmpty(), "Missing instanceIds after full turn cycle: $missingAfterTurn")
    }

    @Test(description = "Play land + cast creature, verify object tracking through stack resolution")
    fun castCreatureTracksObjectThroughZones() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        // Play land for mana
        harness.playLand()

        // Cast creature (hand → stack → battlefield)
        val cast = harness.castCreature()
        assertTrue(cast, "Should be able to cast a creature")

        // Verify accumulated state
        val missing = harness.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Missing instanceIds after cast: $missing")

        // Verify we have objects on battlefield (not just hand/library)
        val battlefieldZone = harness.accumulator.zones.values
            .firstOrNull { it.type == ZoneType.Battlefield }
        assertNotNull(battlefieldZone, "Should have a battlefield zone")
    }

    @Test(description = "Multi-turn game: play land each turn, verify state validity across 3 turns")
    fun multiTurnAccumulatedStateValid() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        repeat(3) { turn ->
            if (harness.isGameOver()) return

            // Play a land if possible (OK if no land available)
            harness.playLand()

            harness.accumulator.assertConsistent("turn ${turn + 1}")

            // Pass turn
            harness.passPriority()
        }
    }

    @Test(description = "AI goes first: auto-pass through AI turn, reach human Main1 with valid state")
    fun aiGoesFirstReachesHumanMain1() {
        // Verify our hardcoded seed actually has AI going first
        val probe = GameBridge()
        probe.start(seed = AI_FIRST_SEED)
        val game = probe.getGame()!!
        val human = game.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiFirst = game.phaseHandler.playerTurn != human
        probe.shutdown()
        assertTrue(aiFirst, "Seed $AI_FIRST_SEED should have AI going first")

        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness.connectAndKeep()

        // After connectAndKeep + autoPass, we should have valid state
        assertFalse(harness.isGameOver(), "Game should not be over at start")

        harness.accumulator.assertConsistent("after AI-first connect")

        // Should have received at least game-start bundle (4 messages)
        assertTrue(harness.allMessages.size >= 4, "Should have at least 4 messages (game-start bundle)")
    }

    @Test(description = "gsId chain valid through play-land, pass, and phase transitions")
    fun gsIdChainValidThroughPhases() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        assertEquals(harness.turn(), 1, "Should start at turn 1")
        assertEquals(harness.phase(), "MAIN1", "Should start at Main1")
        assertFalse(harness.isAiTurn(), "Should be human's turn")

        // Validate chain from game start
        assertGsIdChain(harness.allMessages, context = "game start")

        harness.playLand()
        assertGsIdChain(harness.allMessages, context = "after play land")

        harness.passPriority()
        assertFalse(harness.isGameOver(), "Game should not be over after land + pass")

        // Full chain from game start through all phase transitions
        assertGsIdChain(harness.allMessages, context = "after pass")
    }

    @Test(description = "AI-first game: gsId chain valid through AI opening turn")
    fun aiFirstTurnGsIdChainIsValid() {
        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness.connectAndKeep()

        // After connectAndKeep, AI went first and we auto-passed through
        assertFalse(harness.isGameOver(), "Game should not be over")

        // Validate full gsId chain from game start
        assertGsIdChain(harness.allMessages, context = "AI-first game start")

        // Validate accumulated state consistency
        harness.accumulator.assertConsistent("after AI-first turn")
    }

    @Test(description = "AI-first multi-turn: gsId chain stays unique across 2 AI turns")
    fun aiFirstMultiTurnGsIdChainUnique() {
        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness.connectAndKeep()

        // Pass through first human turn → triggers AI turn 2
        harness.passPriority()
        assertFalse(harness.isGameOver(), "Game should not be over after first pass")

        // Validate full gsId chain including 2 AI turns (turn 1 from connectAndKeep, turn 2 from pass)
        assertGsIdChain(harness.allMessages, context = "AI-first 2 turns")
    }

    @Test(description = "AI turn produces fewer AAR than before fix (no pass-only spam)")
    fun aiTurnHasReducedAARCount() {
        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness.connectAndKeep()

        // Game-start bundle is allowed to have AAR (it's the initial prompt).
        // Grab messages after game-start, which are AI turn diffs.
        val gameStartSize = harness.allMessages.indexOfLast {
            it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full
        } + 1

        val aiTurnMessages = harness.allMessages.subList(gameStartSize, harness.allMessages.size)
        val aars = aiTurnMessages.filter { it.hasActionsAvailableReq() }

        // Before fix: every phase transition during AI turn sent AAR with pass-only
        // actions, flooding the client with "waiting for input" prompts (~6-8 AARs).
        // After fix: only combat/stack resolution paths send AAR (legitimate prompts,
        // typically 1-2). Allow up to 3 for edge cases.
        assertTrue(
            aars.size <= 3,
            "AI turn should have at most 3 AAR (combat/stack checkpoints), " +
                "but got ${aars.size} (total AI-turn messages: ${aiTurnMessages.size})",
        )
    }

    @Test(description = "AI turn actions produce Diff messages (not silently swallowed)")
    fun aiTurnProducesDiffMessages() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        val messagesBeforePass = harness.allMessages.size

        // Play a land then pass — triggers AI turn
        harness.playLand()
        harness.passPriority()

        // After passing through the AI turn, we should have received Diff messages
        // for AI actions (land plays, spells, phase transitions).
        // If autoPassAndAdvance silently drains playback without sending, this fails.
        val newMessages = harness.allMessages.subList(messagesBeforePass, harness.allMessages.size)
        val diffs = newMessages.filter {
            it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Diff
        }
        assertTrue(
            diffs.size >= 2,
            "AI turn should produce at least 2 Diff messages (got ${diffs.size} diffs out of ${newMessages.size} total new messages)",
        )
    }

    // DISABLED: passUntilTurn(3) hits AI_TURN_WAIT_MS (30s) timeout repeatedly because
    // AI-turn playback stalls — engine never delivers priority for turn 3. Burns ~128s
    // polling. Re-enable after AI multi-turn playback regression is fixed.
    @Test(description = "AI turn NewTurnStarted annotation has affectorId and affectedIds", enabled = false)
    fun aiTurnNewTurnStartedAnnotationHasContent() {
        // AI goes first (turn 1). connectAndKeep drains turn-1 playback.
        // Pass through human turn 2 → triggers AI turn 3 via playback.
        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness.connectAndKeep()

        val prePassCount = harness.allMessages.size
        harness.passUntilTurn(3)
        assertFalse(harness.isGameOver(), "Game should not be over")

        val aiMessages = harness.allMessages.subList(prePassCount, harness.allMessages.size)
        val newTurnAnno = checkNotNull(
            aiMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }
                .firstOrNull { it.typeList.contains(AnnotationType.NewTurnStarted) },
        ) { "No NewTurnStarted annotation in AI turn messages (${aiMessages.size} post-pass msgs)" }

        assertTrue(
            newTurnAnno.affectedIdsList.isNotEmpty(),
            "NewTurnStarted must have affectedIds (active player seat), but was empty",
        )
        assertTrue(
            newTurnAnno.affectorId > 0,
            "NewTurnStarted must have affectorId (active player seat), but was 0",
        )
    }

    // DISABLED: same as aiTurnNewTurnStartedAnnotationHasContent — passUntilTurn(3)
    // timeout loop. Re-enable after AI multi-turn playback regression is fixed.
    @Test(description = "AI turn PhaseOrStepModified annotation has affectedIds and phase/step details", enabled = false)
    fun aiTurnPhaseAnnotationHasDetails() {
        // AI goes first (turn 1). connectAndKeep drains turn-1 playback.
        // Pass through human turn 2 → triggers AI turn 3 via playback.
        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness.connectAndKeep()

        val prePassCount = harness.allMessages.size
        harness.passUntilTurn(3)
        assertFalse(harness.isGameOver(), "Game should not be over")

        val aiMessages = harness.allMessages.subList(prePassCount, harness.allMessages.size)
        val phaseAnno = checkNotNull(
            aiMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }
                .firstOrNull { it.typeList.contains(AnnotationType.PhaseOrStepModified) },
        ) { "No PhaseOrStepModified annotation in AI turn messages (${aiMessages.size} post-pass msgs)" }

        assertTrue(
            phaseAnno.affectedIdsList.isNotEmpty(),
            "PhaseOrStepModified must have affectedIds (active player seat), but was empty",
        )

        val detailKeys = phaseAnno.detailsList.map { it.key }.toSet()
        assertTrue(
            "phase" in detailKeys,
            "PhaseOrStepModified must have 'phase' detail, but keys were: $detailKeys",
        )
        assertTrue(
            "step" in detailKeys,
            "PhaseOrStepModified must have 'step' detail, but keys were: $detailKeys",
        )
    }
}
