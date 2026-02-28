package forge.nexus.conformance

import forge.game.zone.ZoneType
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * SBA (state-based action) death conformance: creatures dying to zero
 * toughness, lethal damage, and deathtouch damage should all produce
 * ZoneTransfer annotations with category "Destroy".
 *
 * Source: recording sessions game1 (proxy) — SBA_Damage, SBA_Deathtouch;
 *         recording session 22-31-58 — general SBA deaths observed.
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 * checkStateEffects(true) triggers SBAs inline on the test thread.
 */
@Test(groups = ["conformance"])
class SbaDeathTest : ConformanceTestBase() {

    /** Creature with toughness set to 0 dies to SBA. */
    @Test
    fun zeroToughnessCreatureDiesToSba() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Forest", human, ZoneType.Battlefield)
        }
        val human = game.humanPlayer
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter, checkSba = true) {
            creature.baseToughness = 0
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA zero-toughness death")
        assertEquals(zt!!.category, "Destroy", "SBA zero-toughness BF→GY should produce Destroy category")

        assertTrue(
            human.getZone(ZoneType.Graveyard).cards.any { it.id == forgeCardId },
            "Creature should be in graveyard after SBA",
        )
    }

    /** Creature with lethal damage dies to SBA. */
    @Test
    fun lethalDamageCreatureDiesToSba() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val human = game.humanPlayer
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter, checkSba = true) {
            creature.damage = creature.netToughness
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA lethal damage death")
        assertEquals(zt!!.category, "Destroy", "SBA lethal-damage BF→GY should produce Destroy category")
    }

    /** Creature with deathtouch damage dies to SBA. */
    @Test
    fun deathtouchDamageCreatureDiesToSba() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val human = game.humanPlayer
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterAction(b, game, counter, checkSba = true) {
            creature.damage = 1
            creature.setHasBeenDealtDeathtouchDamage(true)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA deathtouch death")
        assertEquals(zt!!.category, "Destroy", "SBA deathtouch BF→GY should produce Destroy category")
    }

}
