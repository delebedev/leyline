package forge.nexus.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Removal spell flow conformance: simulates removal effects resolving
 * and verifies zone transition annotations use correct categories.
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 */
@Test(groups = ["conformance"])
class RemovalSpellFlowTest : ConformanceTestBase() {

    /** Bounce: BF→Hand produces "Bounce" category. */
    @Test
    fun bounceSpellResolutionProducesBounceCategory() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
            game.action.moveToHand(creature, null)
        }

        val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(forgeCardId))) { "Should have ZoneTransfer annotation for bounced creature" }
        assertEquals(zt.category, "Bounce", "BF→Hand should produce Bounce category")
    }

    /** Destroy: BF→GY produces "Destroy" category. */
    @Test
    fun destroySpellResolutionProducesDestroyCategory() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
            game.action.destroy(creature, null, false, AbilityKey.newMap())
        }

        val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(forgeCardId))) { "Should have ZoneTransfer annotation for destroyed creature" }
        assertEquals(zt.category, "Destroy", "BF→GY via destroy should produce Destroy category")
    }

    /** Exile: BF→Exile produces "Exile" category. */
    @Test
    fun exileSpellResolutionProducesExileCategory() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val forgeCardId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
            game.action.exile(creature, null, AbilityKey.newMap())
        }

        val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(forgeCardId))) { "Should have ZoneTransfer annotation for exiled creature" }
        assertEquals(zt.category, "Exile", "BF→Exile should produce Exile category")
    }

    /**
     * SpellResolved in same event batch doesn't contaminate target category.
     * Regression test: SpellResolved matching must check forgeCardId.
     */
    @Test
    fun spellResolvedDoesNotContaminateTargetCategory() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Swords to Plowshares", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val spellCard = human.getZone(ZoneType.Hand).cards.first()
        val creatureForgeId = creature.id

        val gsm = captureAfterAction(b, game, counter) {
            game.fireEvent(
                forge.game.event.GameEventSpellResolved(spellCard.firstSpellAbility, false),
            )
            game.action.exile(creature, null, AbilityKey.newMap())
        }

        val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(creatureForgeId))) { "Should have ZoneTransfer for exiled creature" }
        assertEquals(zt.category, "Exile", "Target should have Exile category, not Resolve")
    }
}
