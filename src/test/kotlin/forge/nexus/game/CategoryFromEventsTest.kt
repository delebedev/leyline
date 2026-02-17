package forge.nexus.game

import forge.game.zone.ZoneType
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

/**
 * Unit tests for [AnnotationBuilder.categoryFromEvents] — verifies that
 * captured [NexusGameEvent] instances resolve to the correct annotation
 * category strings, matching the behavior of [StateMapper.inferCategory]
 * but using rich event data instead of zone-pair heuristics.
 */
@Test
class CategoryFromEventsTest {

    @Test
    fun landPlayedReturnPlayLand() {
        val events = listOf(
            NexusGameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
            NexusGameEvent.ZoneChanged(forgeCardId = 42, from = ZoneType.Hand, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), "PlayLand")
    }

    @Test
    fun spellCastReturnsCastSpell() {
        val events = listOf(
            NexusGameEvent.SpellCast(forgeCardId = 99, seatId = 1),
            NexusGameEvent.ZoneChanged(forgeCardId = 99, from = ZoneType.Hand, to = ZoneType.Stack),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(99, events), "CastSpell")
    }

    @Test
    fun spellResolvedReturnsResolve() {
        val events = listOf(
            NexusGameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
            NexusGameEvent.ZoneChanged(forgeCardId = 77, from = ZoneType.Stack, to = ZoneType.Battlefield),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), "Resolve")
    }

    @Test
    fun specificEventTakesPriorityOverZoneChanged() {
        // Both LandPlayed and ZoneChanged fire for the same card.
        // LandPlayed should take priority.
        val events = listOf(
            NexusGameEvent.ZoneChanged(forgeCardId = 42, from = ZoneType.Hand, to = ZoneType.Battlefield),
            NexusGameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), "PlayLand")
    }

    @Test
    fun zoneChangedBattlefieldToGraveyardReturnsDestroy() {
        val events = listOf(
            NexusGameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Graveyard),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), "Destroy")
    }

    @Test
    fun zoneChangedBattlefieldToExileReturnsExile() {
        val events = listOf(
            NexusGameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Battlefield, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), "Exile")
    }

    @Test
    fun zoneChangedGenericReturnsZoneTransfer() {
        val events = listOf(
            NexusGameEvent.ZoneChanged(forgeCardId = 55, from = ZoneType.Graveyard, to = ZoneType.Exile),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(55, events), "ZoneTransfer")
    }

    @Test
    fun noMatchingEventReturnsNull() {
        val events = listOf(
            NexusGameEvent.LandPlayed(forgeCardId = 99, seatId = 1),
        )
        assertNull(AnnotationBuilder.categoryFromEvents(42, events))
    }

    @Test
    fun emptyEventsReturnsNull() {
        assertNull(AnnotationBuilder.categoryFromEvents(42, emptyList()))
    }

    @Test
    fun unrelatedEventsIgnored() {
        val events = listOf(
            NexusGameEvent.CardTapped(forgeCardId = 42, tapped = true),
            NexusGameEvent.LifeChanged(seatId = 1, oldLife = 20, newLife = 17),
            NexusGameEvent.DamageDealtToPlayer(sourceForgeId = 10, targetSeatId = 1, amount = 3, combat = true),
        )
        assertNull(AnnotationBuilder.categoryFromEvents(42, events))
    }

    @Test
    fun multipleCardsInEvents() {
        val events = listOf(
            NexusGameEvent.LandPlayed(forgeCardId = 42, seatId = 1),
            NexusGameEvent.SpellCast(forgeCardId = 99, seatId = 1),
            NexusGameEvent.SpellResolved(forgeCardId = 77, hasFizzled = false),
        )
        assertEquals(AnnotationBuilder.categoryFromEvents(42, events), "PlayLand")
        assertEquals(AnnotationBuilder.categoryFromEvents(99, events), "CastSpell")
        assertEquals(AnnotationBuilder.categoryFromEvents(77, events), "Resolve")
        assertNull(AnnotationBuilder.categoryFromEvents(1, events))
    }
}
