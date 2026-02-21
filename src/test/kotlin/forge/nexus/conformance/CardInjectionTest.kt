package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.nexus.game.CardDb
import forge.nexus.game.StateMapper
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.CardType

/**
 * Verifies [TestCardInjector] + [CardDataDeriver] produce cards that are
 * fully visible in proto output with correct metadata.
 */
@Test(groups = ["integration"])
class CardInjectionTest : ConformanceTestBase() {

    @Test(groups = ["integration"])
    fun `injected Serra Angel appears in GSM with correct metadata`() {
        val (b, game, gsId) = startGameAtMain1()
        val injected = TestCardInjector.inject(b, 1, "Serra Angel", ZoneType.Battlefield, sick = false)

        val gsm = StateMapper.buildFromGame(game, gsId + 1, "test", b, viewingSeatId = 1)
        val obj = gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId }

        assertNotNull(obj, "Injected card should appear in gameObjectsList")
        assertEquals(obj!!.grpId, injected.grpId, "grpId should match injected")
        assertTrue(obj.cardTypesList.contains(CardType.Creature), "Serra Angel should be a Creature")
        assertTrue(obj.hasPower(), "Serra Angel should have power")
        assertEquals(obj.power.value, 4, "Serra Angel power should be 4")
        assertTrue(obj.hasToughness(), "Serra Angel should have toughness")
        assertEquals(obj.toughness.value, 4, "Serra Angel toughness should be 4")
        // Flying + Vigilance → at least 2 abilities
        assertTrue(obj.uniqueAbilitiesCount >= 2, "Serra Angel should have at least 2 abilities (Flying + Vigilance)")

        // InstanceIdRegistry consistency
        assertEquals(b.getForgeCardId(injected.instanceId), injected.forgeCardId, "reverse lookup should match")

        // CardDb consistency
        assertNotNull(CardDb.lookup(injected.grpId), "CardDb should have entry for grpId")
        assertEquals(CardDb.getCardName(injected.grpId), "Serra Angel", "CardDb name should match")

        // Accumulator consistency
        val acc = ClientAccumulator()
        acc.seedFull(gsm)
        acc.assertConsistent("after Serra Angel injection")
    }

    @Test(groups = ["integration"])
    fun `injected creature to hand is visible in hand zone`() {
        val (b, game, gsId) = startGameAtMain1()
        val injected = TestCardInjector.inject(b, 1, "Lightning Bolt", ZoneType.Hand)

        val gsm = StateMapper.buildFromGame(game, gsId + 1, "test", b, viewingSeatId = 1)
        val obj = gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId }

        assertNotNull(obj, "Injected card should appear in gameObjectsList")
        assertTrue(obj!!.cardTypesList.contains(CardType.Instant), "Lightning Bolt should be an Instant")

        // Should be in hand zone
        val handZone = gsm.zonesList.firstOrNull { it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Hand && it.ownerSeatId == 1 }
        assertNotNull(handZone, "Hand zone should exist for seat 1")
        assertTrue(
            handZone!!.objectInstanceIdsList.contains(injected.instanceId),
            "Hand zone should contain injected instanceId",
        )
    }

    @Test(groups = ["integration"])
    fun `CardDataDeriver produces consistent grpIds for same card name`() {
        val (b, _, _) = startGameAtMain1()

        val first = TestCardInjector.inject(b, 1, "Grizzly Bears", ZoneType.Battlefield)
        val second = TestCardInjector.inject(b, 1, "Grizzly Bears", ZoneType.Battlefield)

        // Same card name → same grpId
        assertEquals(first.grpId, second.grpId, "Same card name should produce same grpId")
        // Different Forge Card objects → different instanceIds
        assertTrue(first.instanceId != second.instanceId, "Different card objects should get different instanceIds")
        assertTrue(first.forgeCardId != second.forgeCardId, "Different card objects should have different forge IDs")
    }

    @Test(groups = ["integration"])
    fun `auto-register deck list populates CardDb for all cards`() {
        val deckList = "30 Plains\n20 Serra Angel\n10 Lightning Bolt"
        val (_, _, _) = startGameAtMain1(deckList = deckList)

        // All deck cards should be registered in CardDb
        assertNotNull(CardDb.getGrpId("Plains"), "Plains should be registered")
        assertNotNull(CardDb.getGrpId("Serra Angel"), "Serra Angel should be registered")
        assertNotNull(CardDb.getGrpId("Lightning Bolt"), "Lightning Bolt should be registered")
    }

    @Test(groups = ["integration"])
    fun `injected land enters tapped when requested`() {
        val (b, game, gsId) = startGameAtMain1()
        val injected = TestCardInjector.inject(b, 1, "Plains", ZoneType.Battlefield, tapped = true)

        val gsm = StateMapper.buildFromGame(game, gsId + 1, "test", b, viewingSeatId = 1)
        val obj = gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId }

        assertNotNull(obj, "Injected land should appear in gameObjectsList")
        assertTrue(obj!!.cardTypesList.contains(CardType.Land_a80b), "Plains should be a Land")
    }
}
