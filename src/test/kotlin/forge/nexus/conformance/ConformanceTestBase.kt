package forge.nexus.conformance

import forge.game.Game
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.nexus.game.GameBridge
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

    /**
     * Pass priority until the game reaches Main1.
     *
     * After submitting pass, waits for a **fresh** pending action (different actionId)
     * to avoid a race where [GameBridge.awaitPriority] detects a stale pending whose
     * future was already completed but whose `finally` block hasn't cleared it yet.
     * The engine may auto-pass through phases without blocking (smart phase skip),
     * so we also check the phase directly.
     */
    private fun advanceToMain1(b: GameBridge, maxPasses: Int = 20) {
        val game = b.getGame()!!
        var passes = 0
        while (game.phaseHandler.phase != PhaseType.MAIN1 && passes < maxPasses) {
            val pending = awaitFreshPending(b, null)
                ?: error("Timed out waiting for priority while advancing to Main1 (phase=${game.phaseHandler.phase})")
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            passes++
        }
        // Final wait: ensure we have a pending action at MAIN1 before returning
        if (game.phaseHandler.phase == PhaseType.MAIN1) {
            awaitFreshPending(b, null)
        }
    }

    /**
     * Wait for a pending action whose actionId differs from [previousId].
     * Returns null on timeout (15s).
     */
    private fun awaitFreshPending(
        b: GameBridge,
        previousId: String?,
        timeoutMs: Long = 15_000,
    ): forge.web.game.GameActionBridge.PendingAction? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = b.actionBridge.getPending()
            if (p != null && p.actionId != previousId && !p.future.isDone) return p
            Thread.sleep(50)
        }
        return null
    }
}
