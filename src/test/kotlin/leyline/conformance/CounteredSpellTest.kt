package leyline.conformance

import forge.game.zone.ZoneType
import leyline.game.snapshotFromGame
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Countered spell conformance: cast creature → counter it (fizzle) → assert
 * Stack→GY with Countered category, not Resolve.
 *
 * Source: recording #7 (22-24-00/engine) T9 grp:91806 Countered.
 * Bug found during triage: SpellResolved fires with hasFizzled=true but
 * categoryFromEvents returns Resolve before checking zone-pair fallback.
 */
@Test(groups = ["conformance"])
class CounteredSpellTest : ConformanceTestBase() {

    /**
     * Cast a creature, then force-counter it by moving from Stack to GY
     * (simulating a counter spell resolution). The zone transition should
     * produce a ZoneTransfer annotation with category "Countered".
     */
    @Test
    fun counteredCreatureGoesToGraveyardWithCounteredCategory() {
        val (b, game, counter) = startGameAtMain1()

        // Play land for mana
        playLand(b)
        b.snapshotFromGame(game, counter.nextGsId())

        // Cast creature (goes to Stack)
        val player = b.getPlayer(1)!!
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand")
        val forgeCardId = creature.id

        castCreature(b)
        b.snapshotFromGame(game, counter.nextGsId())
        val stackId = b.getOrAllocInstanceId(forgeCardId)

        // Now counter it: move from Stack to Graveyard directly.
        // This simulates what happens when a counterspell resolves — the
        // countered spell moves Stack→GY. Forge fires GameEventCardChangeZone.
        // Stack is a game-level zone in Forge, not per-player.
        val stackCard = game.stackZone.cards.firstOrNull { it.id == forgeCardId }
            ?: error("Creature not found on stack (forgeCardId=$forgeCardId)")

        // Counter the spell via game action (fires SpellResolved with fizzled + zone change)
        val gsm = captureAfterAction(b, game, counter) {
            game.action.moveToGraveyard(stackCard, null)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        // Assert: ZoneTransfer annotation with "Countered" category
        val zt = gsm.findZoneTransfer(newId)
            ?: gsm.findZoneTransfer(stackId) // try old id too
        assertNotNull(zt, "Should have ZoneTransfer annotation for countered spell")
        assertEquals(
            zt!!.category,
            "Countered",
            "Stack→GY should produce Countered category, not Resolve",
        )

        // Creature should be in graveyard, not battlefield
        val bfObjects = gsm.gameObjectsList.filter { it.zoneId > 0 }
        val gyCards = player.getZone(ZoneType.Graveyard).cards
        assertTrue(
            gyCards.any { it.id == forgeCardId },
            "Countered creature should be in graveyard",
        )
    }

    /**
     * When SpellResolved(fizzled=true) fires alongside a Stack→GY zone change,
     * categoryFromEvents should return Countered, not Resolve.
     *
     * SpellResolved match in categoryFromEvents fires first (line 31) and
     * returns Resolve immediately — unless we special-case hasFizzled.
     * This test documents the expected behavior.
     */
    @Test
    fun fizzledSpellResolvedEventProducesCounteredNotResolve() {
        val (b, game, counter) = startGameAtMain1()

        playLand(b)
        b.snapshotFromGame(game, counter.nextGsId())

        val player = b.getPlayer(1)!!
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            ?: error("No creature in hand")
        val forgeCardId = creature.id

        castCreature(b)
        b.snapshotFromGame(game, counter.nextGsId())

        val stackCard = game.stackZone.cards.firstOrNull { it.id == forgeCardId }
            ?: error("Creature not found on stack")

        // Manually fire SpellResolved with fizzled=true (simulates counter)
        // then move to GY — this is what Forge does when a spell is countered.
        val gsm = captureAfterAction(b, game, counter) {
            game.fireEvent(forge.game.event.GameEventSpellResolved(stackCard.firstSpellAbility, true))
            game.action.moveToGraveyard(stackCard, null)
        }
        val newId = b.getOrAllocInstanceId(forgeCardId)

        val zt = gsm.findZoneTransfer(newId)
        assertNotNull(zt, "Should have ZoneTransfer annotation")
        assertEquals(
            zt!!.category,
            "Countered",
            "Fizzled SpellResolved + Stack→GY must produce Countered, not Resolve",
        )
    }
}
