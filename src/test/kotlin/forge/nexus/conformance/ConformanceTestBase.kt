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
 * and capture outbound GRE messages via BundleBuilder.
 */
abstract class ConformanceTestBase {

    protected var bridge: GameBridge? = null

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase()
        TestCardRegistry.ensureRegistered()
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
        val gsId = 20
        b.snapshotState(game, gsId)
        return Triple(b, game, gsId)
    }

    protected fun fingerprint(messages: List<GREToClientMessage>): List<StructuralFingerprint> =
        messages.map { StructuralFingerprint.fromGRE(it) }

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
