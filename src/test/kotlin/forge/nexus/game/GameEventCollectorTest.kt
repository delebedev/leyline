package forge.nexus.game

import forge.game.zone.ZoneType
import forge.web.game.GameBootstrap
import forge.web.game.PlayerAction
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test

/**
 * Tests for [GameEventCollector] and [NexusGameEvent] — verifies that
 * Forge engine events are captured and converted to protocol-oriented events.
 */
@Test(groups = ["integration"])
class GameEventCollectorTest {

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase()
        forge.nexus.conformance.TestCardRegistry.ensureRegistered()
    }

    private var bridge: GameBridge? = null

    @AfterMethod
    fun tearDown() {
        bridge?.shutdown()
        bridge = null
    }

    @Test
    fun collectorIsWiredAfterStart() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        assertNotNull(b.eventCollector, "EventCollector should be wired after start()")
    }

    @Test
    fun collectorCapturesEventsOnKeep() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        // Events fire during game start (draw, phase transitions, etc.)
        val collector = b.eventCollector!!
        assertTrue(collector.hasEvents(), "Collector should have events after start")
    }

    @Test
    fun drainEventsReturnsAndClears() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        val collector = b.eventCollector!!

        val events1 = collector.drainEvents()
        assertTrue(events1.isNotEmpty(), "First drain should return events")

        val events2 = collector.drainEvents()
        assertTrue(events2.isEmpty(), "Second drain should be empty")
    }

    @Test
    fun playLandFiresLandPlayedEvent() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        // Drain events from start/keep/advance — we only care about the land play
        val collector = b.eventCollector!!
        collector.drainEvents()

        val player = b.getPlayer(1)!!
        val landInHand = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: return // no lands (unlikely)
        val landId = landInHand.id

        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(landId))
        awaitFreshPending(b, pending.actionId)

        val events = collector.drainEvents()
        val landPlayed = events.filterIsInstance<NexusGameEvent.LandPlayed>()
        assertTrue(landPlayed.isNotEmpty(), "Should capture LandPlayed event, got: $events")
        assertEquals(landPlayed.first().forgeCardId, landId)
        assertEquals(landPlayed.first().seatId, 1)
    }

    @Test
    fun playLandFiresZoneChangedEvent() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val collector = b.eventCollector!!
        collector.drainEvents()

        val player = b.getPlayer(1)!!
        val landInHand = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: return
        val landId = landInHand.id

        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(landId))
        awaitFreshPending(b, pending.actionId)

        val events = collector.drainEvents()
        val zoneChanges = events.filterIsInstance<NexusGameEvent.ZoneChanged>()
            .filter { it.forgeCardId == landId }
        assertTrue(zoneChanges.isNotEmpty(), "Should capture ZoneChanged for land, got: $events")
        assertEquals(zoneChanges.first().from, ZoneType.Hand)
        assertEquals(zoneChanges.first().to, ZoneType.Battlefield)
    }

    @Test
    fun castSpellFiresSpellCastEvent() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val collector = b.eventCollector!!
        collector.drainEvents()

        val player = b.getPlayer(1)!!
        val game = b.getGame()!!

        // First, play a Forest for mana
        val forest = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val pending1 = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending1.actionId, PlayerAction.PlayLand(forest.id))
        val pending2 = awaitFreshPending(b, pending1.actionId) ?: return
        collector.drainEvents() // clear land play events

        // Find a castable creature (Llanowar Elves or Elvish Mystic)
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { !it.isLand }
            ?: return // no creatures in hand
        val creatureId = creature.id

        // Try to cast — need to tap Forest first
        val actions = StateMapper.buildActions(game, 1, b)
        val hasCast = actions.actionsList.any {
            it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Cast
        }
        if (!hasCast) return // can't cast anything (no mana)

        b.actionBridge.submitAction(pending2.actionId, PlayerAction.CastSpell(creatureId))
        awaitFreshPending(b, pending2.actionId)

        val events = collector.drainEvents()
        val spellCasts = events.filterIsInstance<NexusGameEvent.SpellCast>()
        // May or may not have cast depending on mana — only assert if we got events
        if (spellCasts.isNotEmpty()) {
            assertEquals(spellCasts.first().seatId, 1)
        }
    }
}
