package forge.nexus.conformance

import forge.game.Game
import forge.game.card.Card
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.MessageCounter
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * SBA (state-based action) death conformance: creatures dying to zero
 * toughness, lethal damage, and deathtouch damage should all produce
 * ZoneTransfer annotations with category "Destroy".
 *
 * Source: recording sessions game1 (proxy) — SBA_Damage, SBA_Deathtouch;
 *         recording session 22-31-58 — general SBA deaths observed.
 *
 * Note: zero-toughness SBA bypasses Forge's destroy() method entirely
 * (uses sacrificeDestroy directly), but GameEventCardChangeZone still
 * fires BF→GY which the collector maps to CardDestroyed.
 */
@Test(groups = ["integration", "conformance"])
class SbaDeathTest : ConformanceTestBase() {

    /**
     * Creature with toughness set to 0 dies to SBA.
     * BF→GY transition should have Destroy category.
     */
    @Test
    fun zeroToughnessCreatureDiesToSba() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterSba(b, game, counter) {
            creature.baseToughness = 0
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
            ?: findZoneTransfer(gsm, origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA zero-toughness death")
        assertEquals(
            zt!!.category,
            "Destroy",
            "SBA zero-toughness BF→GY should produce Destroy category",
        )

        // Creature should be in graveyard
        val player = b.getPlayer(1)!!
        assertTrue(
            player.getZone(ZoneType.Graveyard).cards.any { it.id == forgeCardId },
            "Creature should be in graveyard after SBA",
        )
    }

    /**
     * Creature with lethal damage dies to SBA.
     * BF→GY transition should have Destroy category.
     */
    @Test
    fun lethalDamageCreatureDiesToSba() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterSba(b, game, counter) {
            // Mark damage equal to toughness — lethal
            creature.damage = creature.netToughness
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
            ?: findZoneTransfer(gsm, origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA lethal damage death")
        assertEquals(
            zt!!.category,
            "Destroy",
            "SBA lethal-damage BF→GY should produce Destroy category",
        )
    }

    /**
     * Creature with deathtouch damage dies to SBA.
     * BF→GY transition should have Destroy category.
     */
    @Test
    fun deathtouchDamageCreatureDiesToSba() {
        val (b, game, counter) = startGameAtMain1()
        val creature = ensureCreatureOnBattlefield(b, game)
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterSba(b, game, counter) {
            // Any amount of damage + deathtouch flag = lethal
            creature.damage = 1
            creature.setHasBeenDealtDeathtouchDamage(true)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId)
            ?: findZoneTransfer(gsm, origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA deathtouch death")
        assertEquals(
            zt!!.category,
            "Destroy",
            "SBA deathtouch BF→GY should produce Destroy category",
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Get a creature onto the battlefield (play land + cast + resolve). */
    private fun ensureCreatureOnBattlefield(b: GameBridge, game: Game): Card {
        val player = b.getPlayer(1)!!
        val bf = player.getZone(ZoneType.Battlefield)
        bf.cards.firstOrNull { it.isCreature }?.let { return it }

        playLand(b)
        b.snapshotFromGame(game)
        castCreature(b)
        b.snapshotFromGame(game)
        passPriority(b)
        b.snapshotFromGame(game)

        return bf.cards.firstOrNull { it.isCreature }
            ?: error("Failed to get creature on battlefield")
    }

    /**
     * Set up board state via [setup], run SBA check, capture diff.
     * Uses checkStateEffects(true) to trigger state-based actions.
     */
    private fun captureAfterSba(
        b: GameBridge,
        game: Game,
        counter: MessageCounter,
        setup: () -> Unit,
    ): GameStateMessage {
        b.snapshotFromGame(game, counter.currentGsId())
        setup()
        game.action.checkStateEffects(true)
        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        return result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")
    }

    private fun findZoneTransfer(gsm: GameStateMessage, instanceId: Int): ZoneTransferInfo? {
        val ann = gsm.annotationsList.firstOrNull {
            AnnotationType.ZoneTransfer_af5a in it.typeList &&
                instanceId in it.affectedIdsList
        } ?: return null
        return ZoneTransferInfo(
            category = ann.detailsList.firstOrNull { it.key == "category" }?.getValueString(0) ?: "",
        )
    }

    data class ZoneTransferInfo(val category: String)
}
