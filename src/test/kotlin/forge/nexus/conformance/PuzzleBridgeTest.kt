package forge.nexus.conformance

import forge.game.GameStage
import forge.game.GameType
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.nexus.bridge.GameBootstrap
import forge.nexus.bridge.PlayerAction
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.PuzzleSource
import forge.nexus.game.StateMapper
import forge.nexus.game.mapper.ActionMapper
import forge.nexus.server.ListMessageSink
import forge.nexus.server.MatchRegistry
import forge.nexus.server.MatchSession
import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GameStage as ProtoGameStage

/**
 * Integration tests for puzzle mode through [GameBridge.startPuzzle].
 *
 * Verifies:
 * - Puzzle game creation (GameType.Puzzle, age=Play)
 * - Card registration in CardDb + InstanceIdRegistry
 * - Engine reaches priority without mulligan
 * - Life totals and zone contents match puzzle spec
 * - Actions available after puzzle start
 */
class PuzzleBridgeTest {
    private var bridge: GameBridge? = null

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
        CardDb.testMode = true
    }

    @AfterMethod
    fun tearDown() {
        bridge?.shutdown()
        bridge = null
    }

    @Test(groups = ["integration"])
    fun startPuzzleSetsGameTypePuzzle() {
        val b = startPuzzle("puzzles/lands-only.pzl")
        val game = b.getGame()!!
        assertEquals(game.rules.gameType, GameType.Puzzle)
        assertTrue(b.isPuzzle)
    }

    @Test(groups = ["integration"])
    fun startPuzzleSetsGameStagePlay() {
        val b = startPuzzle("puzzles/lands-only.pzl")
        val game = b.getGame()!!
        assertEquals(game.age, GameStage.Play)
    }

    @Test(groups = ["integration"])
    fun startPuzzleReachesMain1() {
        val b = startPuzzle("puzzles/lands-only.pzl")
        val game = b.getGame()!!
        assertEquals(game.phaseHandler.phase, PhaseType.MAIN1)
    }

    @Test(groups = ["integration"])
    fun startPuzzleHasPendingActions() {
        val b = startPuzzle("puzzles/lands-only.pzl")
        val pending = b.actionBridge.getPending()
        assertNotNull(pending, "Should have pending actions after puzzle start")
    }

    @Test(groups = ["integration"])
    fun puzzleCardsRegisteredInCardDb() {
        val b = startPuzzle("puzzles/simple-attack.pzl")
        // Grizzly Bears should be registered
        val grpId = CardDb.lookupByName("Grizzly Bears")
        assertNotNull(grpId, "Grizzly Bears should be registered in CardDb")
        assertTrue(grpId!! > 0, "grpId should be positive")
    }

    @Test(groups = ["integration"])
    fun puzzleCardsRegisteredInInstanceIdRegistry() {
        val b = startPuzzle("puzzles/simple-attack.pzl")
        val game = b.getGame()!!
        val human = b.getPlayer(1)!!
        val bears = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
        val instanceId = b.getOrAllocInstanceId(bears.id)
        assertTrue(instanceId > 0, "instanceId should be allocated")
        // Verify reverse lookup
        val forgeId = b.getForgeCardId(instanceId)
        assertEquals(forgeId, bears.id)
    }

    @Test(groups = ["integration"])
    fun puzzleLifeTotalsMatchSpec() {
        val b = startPuzzle("puzzles/custom-life.pzl")
        val human = b.getPlayer(1)!!
        val ai = b.getPlayer(2)!!
        assertEquals(human.life, 7, "Human life should be 7")
        assertEquals(ai.life, 1, "AI life should be 1")
    }

    @Test(groups = ["integration"])
    fun puzzleBattlefieldMatchesSpec() {
        val b = startPuzzle("puzzles/simple-attack.pzl")
        val human = b.getPlayer(1)!!
        val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
        assertTrue("Grizzly Bears" in battlefield, "Grizzly Bears should be on battlefield")
        assertTrue("Forest" in battlefield, "Forest should be on battlefield")
    }

    @Test(groups = ["integration"])
    fun puzzleHandMatchesSpec() {
        val b = startPuzzle("puzzles/simple-attack.pzl")
        val human = b.getPlayer(1)!!
        val hand = human.getZone(ZoneType.Hand).cards.map { it.name }
        assertTrue("Giant Growth" in hand, "Giant Growth should be in hand")
    }

    @Test(groups = ["integration"])
    fun puzzleBuildFromGameHasStagePlay() {
        val b = startPuzzle("puzzles/lands-only.pzl")
        val game = b.getGame()!!
        val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
        assertEquals(gsm.gameInfo.stage, ProtoGameStage.Play_a920)
    }

    @Test(groups = ["integration"])
    fun puzzleBuildFromGameHasCorrectLifeTotals() {
        val b = startPuzzle("puzzles/custom-life.pzl")
        val game = b.getGame()!!
        val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
        val p1 = gsm.playersList.first { it.systemSeatNumber == 1 }
        val p2 = gsm.playersList.first { it.systemSeatNumber == 2 }
        assertEquals(p1.lifeTotal, 7, "Player 1 life should be 7")
        assertEquals(p2.lifeTotal, 1, "Player 2 life should be 1")
    }

    @Test(groups = ["integration"])
    fun puzzleBuildFromGameHasBattlefieldObjects() {
        val b = startPuzzle("puzzles/simple-attack.pzl")
        val game = b.getGame()!!
        val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
        // Should have game objects for battlefield creatures and lands
        assertTrue(gsm.gameObjectsCount > 0, "Should have game objects")
    }

    @Test(groups = ["integration"])
    fun puzzleCanPerformAction() {
        val b = startPuzzle("puzzles/lands-only.pzl")
        // The puzzle should have actions available (at least Pass)
        val pending = b.actionBridge.getPending()
        assertNotNull(pending, "Should have pending action")
        // Pass priority
        b.actionBridge.submitAction(pending!!.actionId, PlayerAction.PassPriority)
        b.awaitPriority()
        // Should reach another priority stop or advance phases
        // (just verifying the engine doesn't crash)
    }

    // --- WEB_ puzzle smoke tests ---

    @Test(groups = ["integration"])
    fun webTest00OneBoltLoads() {
        val b = startPuzzle("puzzles/bolt-face.pzl")
        val game = b.getGame()!!
        assertTrue(b.isPuzzle)
        assertEquals(game.phaseHandler.phase, PhaseType.MAIN1)
        // Human: 1 Mountain on battlefield, Lightning Bolt in hand
        val human = b.getPlayer(1)!!
        val hand = human.getZone(ZoneType.Hand).cards.map { it.name }
        val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
        assertTrue("Lightning Bolt" in hand, "Lightning Bolt should be in hand")
        assertTrue("Mountain" in battlefield, "Mountain should be on battlefield")
        // AI at 3 life
        val ai = b.getPlayer(2)!!
        assertEquals(ai.life, 3, "AI life should be 3")
        // Should have actions (Cast Lightning Bolt + Pass at minimum)
        val pending = b.actionBridge.getPending()
        assertNotNull(pending, "Should have pending actions")
    }

    @Test(groups = ["integration"])
    fun webTest00ProducesValidGsm() {
        val b = startPuzzle("puzzles/bolt-face.pzl")
        val game = b.getGame()!!
        val gsm = StateMapper.buildFromGame(game, 1, "test-puzzle", b, viewingSeatId = 1)
        assertEquals(gsm.gameInfo.stage, ProtoGameStage.Play_a920)
        // Should have game objects (Mountain on bf, Lightning Bolt in hand)
        assertTrue(gsm.gameObjectsCount >= 2, "Expected at least 2 game objects, got ${gsm.gameObjectsCount}")
        // Life totals
        val p1 = gsm.playersList.first { it.systemSeatNumber == 1 }
        val p2 = gsm.playersList.first { it.systemSeatNumber == 2 }
        assertEquals(p1.lifeTotal, 20)
        assertEquals(p2.lifeTotal, 3)
    }

    @Test(groups = ["integration"])
    fun webTest00ActionsIncludeCast() {
        val b = startPuzzle("puzzles/bolt-face.pzl")
        val game = b.getGame()!!
        val actions = ActionMapper.buildActions(game, 1, b)
        val actionTypes = actions.actionsList.map { it.actionType.name }
        assertTrue(
            actionTypes.any { it == "Cast" },
            "Should be able to Cast Lightning Bolt, got: $actionTypes",
        )
    }

    // --- Puzzle race bug (seat 2 auto-passes seat 1's pending action) ---

    /**
     * Regression test: when both seats connect in puzzle mode (Sparky path),
     * seat 2's [MatchSession.onPuzzleStart] must NOT consume seat 1's pending
     * priority action. Before the fix, seat 2's [AutoPassEngine] saw
     * isAiTurn=true (the turn owner is seat 1's player, not seat 2's) and
     * auto-passed the human's pending action, advancing the engine past Main1
     * into Combat.
     *
     * See BUGS.md "Sparky/AI bot path starts in Combat instead of Main1".
     */
    @Test(groups = ["integration"])
    fun seat2OnPuzzleStartDoesNotAdvancePastMain1() {
        val puzzle = PuzzleSource.loadFromResource("puzzles/lands-only.pzl")
        val registry = MatchRegistry()
        val matchId = "test-puzzle-race"

        // Create shared bridge — startPuzzle blocks until Main1 priority
        val b = GameBridge(bridgeTimeoutMs = 5_000)
        bridge = b
        b.priorityWaitMs = 5_000
        b.startPuzzle(puzzle)
        assertEquals(b.getGame()!!.phaseHandler.phase, PhaseType.MAIN1)

        // Seat 1 (human): connect and run onPuzzleStart — normal path
        val sink1 = ListMessageSink()
        val session1 = MatchSession(
            seatId = 1,
            matchId = matchId,
            sink = sink1,
            registry = registry,
            paceDelayMs = 0,
        )
        session1.connectBridge(b)
        registry.registerSession(matchId, 1, session1)
        session1.onPuzzleStart()
        assertEquals(
            b.getGame()!!.phaseHandler.phase,
            PhaseType.MAIN1,
            "Phase should be Main1 after seat 1 onPuzzleStart",
        )

        val turnBefore = b.getGame()!!.phaseHandler.turn

        // Seat 2 (Familiar): connect and run onPuzzleStart — this is the race
        val sink2 = ListMessageSink()
        val session2 = MatchSession(
            seatId = 2,
            matchId = matchId,
            sink = sink2,
            registry = registry,
            paceDelayMs = 0,
        )
        session2.connectBridge(b)
        registry.registerSession(matchId, 2, session2)
        session2.onPuzzleStart()

        // Turn must not have advanced — seat 2 must not consume seat 1's actions
        assertEquals(
            b.getGame()!!.phaseHandler.turn,
            turnBefore,
            "Turn should not advance after seat 2 onPuzzleStart (race bug)",
        )
        // Phase must still be Main1
        assertEquals(
            b.getGame()!!.phaseHandler.phase,
            PhaseType.MAIN1,
            "Phase should still be Main1 after seat 2 onPuzzleStart (race bug)",
        )
        // Seat 1's pending action must still be available
        assertNotNull(
            b.actionBridge.getPending(),
            "Seat 1 should still have pending actions after seat 2 puzzle start",
        )
    }

    // --- Helpers ---

    private fun startPuzzle(resourcePath: String): GameBridge {
        val puzzle = PuzzleSource.loadFromResource(resourcePath)
        val b = GameBridge(bridgeTimeoutMs = 5_000)
        bridge = b
        b.priorityWaitMs = 5_000
        b.startPuzzle(puzzle)
        return b
    }
}
