package forge.nexus.game

import forge.game.zone.ZoneType
import forge.nexus.conformance.ConformanceTestBase
import forge.nexus.game.mapper.ZoneIds
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages

/**
 * Shape tests for [StateMapper] output — zone visibility, timers, player info.
 * Board-based (no game loop needed).
 */
@Test(groups = ["conformance"])
class StateMapperShapeTest : ConformanceTestBase() {

    /**
     * Full state must have timers (real client: 2 inactivity timers).
     * Client may lock out or hide turn timer without them.
     */
    @Test
    fun fullStateHasTimers() {
        val (b, game) = startWithBoard { _, _, _ -> }

        val gs = StateMapper.buildFromGame(game, 1, TEST_MATCH_ID, b)

        assertTrue(gs.timersCount >= 2, "Full state should have at least 2 timers")
        val timer1 = gs.timersList.first { it.timerId == 1 }
        val timer2 = gs.timersList.first { it.timerId == 2 }
        assertEquals(timer1.type, Messages.TimerType.Inactivity_a5e2)
        assertEquals(timer2.type, Messages.TimerType.Inactivity_a5e2)
        assertTrue(timer1.durationSec > 0, "Timer duration must be positive")
    }

    /**
     * Zone visibility must match real client:
     * Suppressed/Pending = Public, Sideboard = Private.
     */
    @Test
    fun zoneVisibilityMatchesRealClient() {
        val (b, game) = startWithBoard { g, human, _ ->
            // Add cards to hand and graveyard so we can check object visibility
            addCard("Forest", human, ZoneType.Hand)
            addCard("Forest", human, ZoneType.Graveyard)
        }

        val gs = StateMapper.buildFromGame(game, 1, TEST_MATCH_ID, b)

        val byId = gs.zonesList.associateBy { it.zoneId }
        // Real client: Suppressed + Pending are Public
        assertEquals(byId[ZoneIds.SUPPRESSED]!!.visibility, Messages.Visibility.Public, "Suppressed should be Public")
        assertEquals(byId[ZoneIds.PENDING]!!.visibility, Messages.Visibility.Public, "Pending should be Public")
        // Real client: Sideboard is Private
        assertEquals(byId[ZoneIds.P1_SIDEBOARD]!!.visibility, Messages.Visibility.Private, "P1 Sideboard should be Private")
        assertEquals(byId[ZoneIds.P2_SIDEBOARD]!!.visibility, Messages.Visibility.Private, "P2 Sideboard should be Private")

        // Graveyard objects must be Public (rosetta.md Table 3)
        val gyObjects = gs.gameObjectsList.filter { obj ->
            obj.zoneId == ZoneIds.P1_GRAVEYARD || obj.zoneId == ZoneIds.P2_GRAVEYARD
        }
        for (obj in gyObjects) {
            assertEquals(obj.visibility, Messages.Visibility.Public, "Graveyard GameObjectInfo should be Public (zoneId=${obj.zoneId})")
        }

        // Hand objects must be Private
        val handObjects = gs.gameObjectsList.filter { obj ->
            obj.zoneId == ZoneIds.P1_HAND || obj.zoneId == ZoneIds.P2_HAND
        }
        for (obj in handObjects) {
            assertEquals(obj.visibility, Messages.Visibility.Private, "Hand GameObjectInfo should be Private (zoneId=${obj.zoneId})")
        }
    }

    /**
     * PlayerInfo must include timerIds (real client: timerIds=[seatId]).
     */
    @Test
    fun playerInfoHasTimerIds() {
        val (b, game) = startWithBoard { _, _, _ -> }

        val gs = StateMapper.buildFromGame(game, 1, TEST_MATCH_ID, b)

        for (player in gs.playersList) {
            assertTrue(
                player.timerIdsCount > 0,
                "Player seat ${player.systemSeatNumber} must have timerIds",
            )
            assertEquals(
                player.timerIdsList[0],
                player.systemSeatNumber,
                "timerIds[0] should equal seat number",
            )
        }
    }
}
