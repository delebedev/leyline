package forge.nexus.conformance

import forge.game.Game
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.nexus.game.GameBridge
import forge.nexus.game.advanceToMain1
import forge.nexus.game.awaitFreshPending
import forge.web.game.GameBootstrap
import forge.web.game.PlayerAction
import org.testng.Assert
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Base class for wire conformance tests.
 *
 * Provides helpers to start deterministic games, play actions,
 * capture outbound GRE messages via BundleBuilder, and compare
 * fingerprints against golden files.
 */
abstract class ConformanceTestBase {

    protected var bridge: GameBridge? = null

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase()
    }

    @AfterMethod
    fun tearDown() {
        bridge?.shutdown()
        bridge = null
    }

    /** Start a deterministic game, keep hand, advance to Main1. Returns (bridge, game, startingGsId). */
    protected fun startGameAtMain1(seed: Long = 42L): Triple<GameBridge, Game, Int> {
        val b = GameBridge()
        bridge = b
        b.start(seed = seed)
        b.submitKeep(1)
        advanceToMain1(b)
        val game = b.getGame()!!
        Assert.assertEquals(
            game.phaseHandler.phase,
            PhaseType.MAIN1,
            "Game should be at Main1 after advanceToMain1 (actual: ${game.phaseHandler.phase})",
        )
        b.snapshotState(game)
        return Triple(b, game, 20)
    }

    protected fun fingerprint(messages: List<GREToClientMessage>): List<StructuralFingerprint> =
        messages.map { StructuralFingerprint.fromGRE(it) }

    protected fun loadGolden(name: String): List<StructuralFingerprint> =
        GoldenSequence.fromResource("golden/$name.json")

    protected fun assertConformance(goldenName: String, captured: List<StructuralFingerprint>) {
        val golden = loadGolden(goldenName)
        val result = StructuralDiff.compare(golden, captured)
        if (!result.matches) {
            Assert.fail(
                "Wire conformance FAILED for '$goldenName':\n${result.report()}\n" +
                    "Captured:\n${formatFingerprints(captured)}",
            )
        }
    }

    /**
     * Shape-only conformance: checks message types, gsType, updateType, annotations,
     * prompts. Ignores deck-dependent action types and allows extra field presence.
     */
    protected fun assertShapeConformance(goldenName: String, captured: List<StructuralFingerprint>) {
        val golden = loadGolden(goldenName)
        val result = StructuralDiff.compareShape(golden, captured)
        if (!result.matches) {
            Assert.fail(
                "Wire shape conformance FAILED for '$goldenName':\n${result.report()}\n" +
                    "Captured:\n${formatFingerprints(captured)}",
            )
        }
    }

    protected fun saveGolden(name: String, captured: List<StructuralFingerprint>) {
        val file = java.io.File("src/test/resources/golden/$name.json")
        file.parentFile.mkdirs()
        file.writeText(GoldenSequence.toJson(captured))
    }

    protected fun formatFingerprints(fps: List<StructuralFingerprint>): String =
        fps.withIndex().joinToString("\n") { (i, fp) ->
            "  [$i] ${fp.greMessageType} gsType=${fp.gsType} update=${fp.updateType} " +
                "annotations=${fp.annotationTypes} categories=${fp.annotationCategories} " +
                "actions=${fp.actionTypes} prompt=${fp.promptId}"
        }

    protected fun playLand(b: GameBridge): PlayerAction? {
        val player = b.getPlayer(1) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.PlayLand(land.id)
        b.actionBridge.submitAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    protected fun castCreature(b: GameBridge): PlayerAction? {
        val player = b.getPlayer(1) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.CastSpell(creature.id)
        b.actionBridge.submitAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    protected fun passPriority(b: GameBridge) {
        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
        awaitFreshPending(b, pending.actionId)
    }
}
