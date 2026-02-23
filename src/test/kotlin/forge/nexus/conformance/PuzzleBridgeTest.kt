package forge.nexus.conformance

import forge.game.GameStage
import forge.game.GameType
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.PuzzleSource
import forge.nexus.game.StateMapper
import forge.web.game.GameBootstrap
import forge.web.game.PlayerAction
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
        val b = startPuzzle("puzzles/WEB_TEST_00.pzl")
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
        val b = startPuzzle("puzzles/WEB_TEST_00.pzl")
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
        val b = startPuzzle("puzzles/WEB_TEST_00.pzl")
        val game = b.getGame()!!
        val actions = StateMapper.buildActions(game, 1, b)
        val actionTypes = actions.actionsList.map { it.actionType.name }
        assertTrue(
            actionTypes.any { it == "Cast" },
            "Should be able to Cast Lightning Bolt, got: $actionTypes",
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
