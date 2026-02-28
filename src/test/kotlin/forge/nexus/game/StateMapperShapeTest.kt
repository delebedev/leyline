package forge.nexus.game

import forge.game.zone.ZoneType
import forge.nexus.conformance.ConformanceTestBase
import forge.nexus.game.mapper.ZoneIds
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

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

    /** buildFromGame produces zones, game objects, and turnInfo. */
    @Test
    fun buildFromGameProducesValidState() {
        val (b, game) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
            addCard("Forest", human, ZoneType.Hand)
            addCard("Llanowar Elves", human, ZoneType.Hand)
        }

        val gs = StateMapper.buildFromGame(game, 1, TEST_MATCH_ID, b)

        assertTrue(gs.zonesCount > 0, "GameState should have zones")
        assertTrue(gs.gameObjectsCount > 0, "GameState should have game objects")

        val handZone = gs.zonesList.find { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
        checkNotNull(handZone) { "Should have seat 1 hand zone" }
        assertEquals(handZone.objectInstanceIdsCount, 3, "Hand should have 3 cards")

        assertTrue(gs.hasTurnInfo(), "Should have turn info")
    }

    /** Hand cards have cardTypes, supertypes, subtypes, power/toughness. */
    @Test
    fun gameObjectsHaveCardTypeFields() {
        val (b, game) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
            addCard("Llanowar Elves", human, ZoneType.Hand)
        }

        val gs = StateMapper.buildFromGame(game, 1, TEST_MATCH_ID, b)

        val handZone = gs.zonesList.first { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
        val handInstanceIds = handZone.objectInstanceIdsList.toSet()
        val handObjects = gs.gameObjectsList.filter { it.instanceId in handInstanceIds }
        assertTrue(handObjects.isNotEmpty(), "Should have hand objects")

        // Every hand card should have at least one cardType
        for (obj in handObjects) {
            assertTrue(
                obj.cardTypesCount > 0,
                "Hand card instanceId=${obj.instanceId} grpId=${obj.grpId} missing cardTypes",
            )
        }

        // Forest: Land + Basic + Forest subtype
        val lands = handObjects.filter {
            it.cardTypesList.contains(Messages.CardType.Land_a80b)
        }
        assertTrue(lands.isNotEmpty(), "Should have land in hand")
        for (land in lands) {
            assertTrue(
                land.superTypesList.contains(Messages.SuperType.Basic),
                "Forest should have Basic supertype",
            )
            assertTrue(
                land.subtypesList.contains(Messages.SubType.Forest),
                "Forest should have Forest subtype",
            )
        }

        // Llanowar Elves: Creature with power/toughness
        val creatures = handObjects.filter {
            it.cardTypesList.contains(Messages.CardType.Creature)
        }
        assertTrue(creatures.isNotEmpty(), "Should have creature in hand")
        for (c in creatures) {
            assertTrue(c.hasPower(), "Creature instanceId=${c.instanceId} missing power")
            assertTrue(c.hasToughness(), "Creature instanceId=${c.instanceId} missing toughness")
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
