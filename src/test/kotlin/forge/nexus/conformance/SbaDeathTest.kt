package forge.nexus.conformance

import forge.game.Game
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
        val human = game.players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterSba(b, game, counter) {
            creature.baseToughness = 0
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId) ?: findZoneTransfer(gsm, origId)
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
        val human = game.players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterSba(b, game, counter) {
            creature.damage = creature.netToughness
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId) ?: findZoneTransfer(gsm, origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA lethal damage death")
        assertEquals(zt!!.category, "Destroy", "SBA lethal-damage BF→GY should produce Destroy category")
    }

    /** Creature with deathtouch damage dies to SBA. */
    @Test
    fun deathtouchDamageCreatureDiesToSba() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val human = game.players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id
        val origId = b.getOrAllocInstanceId(forgeCardId)

        val gsm = captureAfterSba(b, game, counter) {
            creature.damage = 1
            creature.setHasBeenDealtDeathtouchDamage(true)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = findZoneTransfer(gsm, newId) ?: findZoneTransfer(gsm, origId)
        assertNotNull(zt, "Should have ZoneTransfer for SBA deathtouch death")
        assertEquals(zt!!.category, "Destroy", "SBA deathtouch BF→GY should produce Destroy category")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
