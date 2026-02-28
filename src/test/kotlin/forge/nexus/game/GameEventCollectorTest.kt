package forge.nexus.game

import forge.game.card.CounterEnumType
import forge.game.event.*
import forge.game.zone.ZoneType
import forge.nexus.conformance.ConformanceTestBase
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Tests for [GameEventCollector] — verifies that Forge engine events are
 * captured and converted to the correct [NexusGameEvent] variants.
 *
 * Uses startWithBoard{} — fires events directly via game.fireEvent(),
 * then asserts on collector.drainEvents(). ~0.01s per test.
 */
@Test(groups = ["conformance"])
class GameEventCollectorTest : ConformanceTestBase() {

    // -- infrastructure --

    @Test
    fun collectorIsWiredAfterWrapGame() {
        val (b, _, _) = startWithBoard { _, _, _ -> }
        assertTrue(b.eventCollector != null, "EventCollector should be wired after wrapGame()")
    }

    @Test
    fun drainEventsReturnsAndClears() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }
        val collector = b.eventCollector!!

        // startWithBoard fires some events during setup
        collector.drainEvents()

        // Fire a simple event
        game.fireEvent(GameEventShuffle(game.humanPlayer))
        val events1 = collector.drainEvents()
        assertTrue(events1.isNotEmpty(), "First drain should return events")

        val events2 = collector.drainEvents()
        assertTrue(events2.isEmpty(), "Second drain should be empty")
    }

    // -- LandPlayed --

    @Test
    fun landPlayedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val land = game.humanPlayer.getZone(ZoneType.Hand).cards.first { it.isLand }
        game.fireEvent(GameEventLandPlayed(game.humanPlayer, land))

        val events = collector.drainEvents()
        val lp = events.filterIsInstance<NexusGameEvent.LandPlayed>()
        assertEquals(lp.size, 1)
        assertEquals(lp[0].forgeCardId, land.id)
        assertEquals(lp[0].seatId, 1)
    }

    // -- SpellCast --

    @Test
    fun spellCastEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
        game.fireEvent(GameEventSpellAbilityCast(spell.firstSpellAbility, null, 0))

        val events = collector.drainEvents()
        val sc = events.filterIsInstance<NexusGameEvent.SpellCast>()
        assertEquals(sc.size, 1)
        assertEquals(sc[0].forgeCardId, spell.id)
        assertEquals(sc[0].seatId, 1)
    }

    // -- SpellResolved --

    @Test
    fun spellResolvedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
        game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))

        val events = collector.drainEvents()
        val sr = events.filterIsInstance<NexusGameEvent.SpellResolved>()
        assertEquals(sr.size, 1)
        assertEquals(sr[0].forgeCardId, spell.id)
        assertFalse(sr[0].hasFizzled)
    }

    @Test
    fun spellResolvedFizzled() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
        game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, true))

        val sr = collector.drainEvents().filterIsInstance<NexusGameEvent.SpellResolved>()
        assertEquals(sr.size, 1)
        assertTrue(sr[0].hasFizzled)
    }

    // -- CardChangeZone: specific variants --

    @Test
    fun cardChangeZone_battlefieldToGraveyard_emitsCardDestroyed() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
        val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
        game.fireEvent(GameEventCardChangeZone(creature, bf, gy))

        val events = collector.drainEvents()
        val destroyed = events.filterIsInstance<NexusGameEvent.CardDestroyed>()
        assertEquals(destroyed.size, 1, "BF→GY should emit CardDestroyed, got: $events")
        assertEquals(destroyed[0].forgeCardId, creature.id)
        assertEquals(destroyed[0].seatId, 1)
    }

    @Test
    fun cardChangeZone_battlefieldToHand_emitsCardBounced() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
        val hand = game.humanPlayer.getZone(ZoneType.Hand)
        game.fireEvent(GameEventCardChangeZone(creature, bf, hand))

        val bounced = collector.drainEvents().filterIsInstance<NexusGameEvent.CardBounced>()
        assertEquals(bounced.size, 1, "BF→Hand should emit CardBounced")
        assertEquals(bounced[0].forgeCardId, creature.id)
    }

    @Test
    fun cardChangeZone_anyToExile_emitsCardExiled() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
        val exile = game.humanPlayer.getZone(ZoneType.Exile)
        game.fireEvent(GameEventCardChangeZone(creature, bf, exile))

        val exiled = collector.drainEvents().filterIsInstance<NexusGameEvent.CardExiled>()
        assertEquals(exiled.size, 1, "→Exile should emit CardExiled")
    }

    @Test
    fun cardChangeZone_handToGraveyard_emitsCardDiscarded() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val card = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
        val hand = game.humanPlayer.getZone(ZoneType.Hand)
        val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
        game.fireEvent(GameEventCardChangeZone(card, hand, gy))

        val discarded = collector.drainEvents().filterIsInstance<NexusGameEvent.CardDiscarded>()
        assertEquals(discarded.size, 1, "Hand→GY should emit CardDiscarded")
    }

    @Test
    fun cardChangeZone_libraryToGraveyard_emitsCardMilled() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Library)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val card = game.humanPlayer.getZone(ZoneType.Library).cards.first()
        val lib = game.humanPlayer.getZone(ZoneType.Library)
        val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
        game.fireEvent(GameEventCardChangeZone(card, lib, gy))

        val milled = collector.drainEvents().filterIsInstance<NexusGameEvent.CardMilled>()
        assertEquals(milled.size, 1, "Library→GY should emit CardMilled")
    }

    @Test
    fun cardChangeZone_genericFallback_emitsZoneChanged() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Graveyard)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        // GY→Library is not a specific variant — should fall back to ZoneChanged
        val card = game.humanPlayer.getZone(ZoneType.Graveyard).cards.first()
        val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
        val lib = game.humanPlayer.getZone(ZoneType.Library)
        game.fireEvent(GameEventCardChangeZone(card, gy, lib))

        val zc = collector.drainEvents().filterIsInstance<NexusGameEvent.ZoneChanged>()
        assertEquals(zc.size, 1, "GY→Library should emit generic ZoneChanged")
        assertEquals(zc[0].from, ZoneType.Graveyard)
        assertEquals(zc[0].to, ZoneType.Library)
    }

    // -- CardTapped --

    @Test
    fun cardTappedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val land = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first()
        game.fireEvent(GameEventCardTapped(land, true))

        val tapped = collector.drainEvents().filterIsInstance<NexusGameEvent.CardTapped>()
        assertEquals(tapped.size, 1)
        assertEquals(tapped[0].forgeCardId, land.id)
        assertTrue(tapped[0].tapped)
    }

    @Test
    fun cardUntappedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val land = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first()
        game.fireEvent(GameEventCardTapped(land, false))

        val tapped = collector.drainEvents().filterIsInstance<NexusGameEvent.CardTapped>()
        assertEquals(tapped.size, 1)
        assertFalse(tapped[0].tapped)
    }

    // -- Damage --

    @Test
    fun damageDealtToCardEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Serra Angel", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val cards = game.humanPlayer.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
        val source = cards[0]
        val target = cards[1]
        game.fireEvent(GameEventCardDamaged(target, source, 2, GameEventCardDamaged.DamageType.Normal))

        val dmg = collector.drainEvents().filterIsInstance<NexusGameEvent.DamageDealtToCard>()
        assertEquals(dmg.size, 1)
        assertEquals(dmg[0].sourceForgeId, source.id)
        assertEquals(dmg[0].targetForgeId, target.id)
        assertEquals(dmg[0].amount, 2)
    }

    @Test
    fun damageDealtToPlayerEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        game.fireEvent(GameEventPlayerDamaged(game.humanPlayer, creature, 3, true, false))

        val dmg = collector.drainEvents().filterIsInstance<NexusGameEvent.DamageDealtToPlayer>()
        assertEquals(dmg.size, 1)
        assertEquals(dmg[0].sourceForgeId, creature.id)
        assertEquals(dmg[0].targetSeatId, 1)
        assertEquals(dmg[0].amount, 3)
        assertTrue(dmg[0].combat)
    }

    // -- LifeChanged --

    @Test
    fun lifeChangedEvent() {
        val (b, game, _) = startWithBoard { _, _, _ -> }
        val collector = b.eventCollector!!
        collector.drainEvents()

        game.fireEvent(GameEventPlayerLivesChanged(game.humanPlayer, 20, 17))

        val lc = collector.drainEvents().filterIsInstance<NexusGameEvent.LifeChanged>()
        assertEquals(lc.size, 1)
        assertEquals(lc[0].seatId, 1)
        assertEquals(lc[0].oldLife, 20)
        assertEquals(lc[0].newLife, 17)
    }

    // -- CardSacrificed --

    @Test
    fun cardSacrificedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        game.fireEvent(GameEventCardSacrificed(creature))

        val sac = collector.drainEvents().filterIsInstance<NexusGameEvent.CardSacrificed>()
        assertEquals(sac.size, 1)
        assertEquals(sac[0].forgeCardId, creature.id)
        assertEquals(sac[0].seatId, 1)
    }

    // -- Attachment --

    @Test
    fun cardAttachedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Holy Strength", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val cards = game.humanPlayer.getZone(ZoneType.Battlefield).cards.toList()
        val aura = cards.first { !it.isCreature }
        val creature = cards.first { it.isCreature }
        game.fireEvent(GameEventCardAttachment(aura, null, creature))

        val attached = collector.drainEvents().filterIsInstance<NexusGameEvent.CardAttached>()
        assertEquals(attached.size, 1)
        assertEquals(attached[0].forgeCardId, aura.id)
        assertEquals(attached[0].targetForgeId, creature.id)
    }

    @Test
    fun cardDetachedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Holy Strength", human, ZoneType.Battlefield)
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val aura = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { !it.isCreature }
        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        game.fireEvent(GameEventCardAttachment(aura, creature, null))

        val detached = collector.drainEvents().filterIsInstance<NexusGameEvent.CardDetached>()
        assertEquals(detached.size, 1)
        assertEquals(detached[0].forgeCardId, aura.id)
    }

    // -- Counters --

    @Test
    fun countersChangedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        game.fireEvent(GameEventCardCounters(creature, CounterEnumType.P1P1, 0, 2))

        val cc = collector.drainEvents().filterIsInstance<NexusGameEvent.CountersChanged>()
        assertEquals(cc.size, 1)
        assertEquals(cc[0].forgeCardId, creature.id)
        assertEquals(cc[0].counterType, "+1/+1")
        assertEquals(cc[0].oldCount, 0)
        assertEquals(cc[0].newCount, 2)
    }

    // -- P/T changed --

    @Test
    fun powerToughnessChangedEvent() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

        // First event establishes baseline (no prior cached P/T → no delta)
        game.fireEvent(GameEventCardStatsChanged(creature))
        collector.drainEvents() // baseline, may or may not emit

        // Pump the creature, fire again
        creature.setBasePower(creature.getNetPower() + 2)
        game.fireEvent(GameEventCardStatsChanged(creature))

        val pt = collector.drainEvents().filterIsInstance<NexusGameEvent.PowerToughnessChanged>()
        assertEquals(pt.size, 1, "Should detect P/T delta")
        assertEquals(pt[0].forgeCardId, creature.id)
        assertEquals(pt[0].newPower, creature.getNetPower())
    }

    @Test
    fun powerToughnessUnchanged_noDuplicate() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

        // Fire twice with same stats — second should not emit
        game.fireEvent(GameEventCardStatsChanged(creature))
        collector.drainEvents()
        game.fireEvent(GameEventCardStatsChanged(creature))

        val pt = collector.drainEvents().filterIsInstance<NexusGameEvent.PowerToughnessChanged>()
        assertTrue(pt.isEmpty(), "Same P/T should not re-emit")
    }

    // -- Shuffle --

    @Test
    fun libraryShuffledEvent() {
        val (b, game, _) = startWithBoard { _, _, _ -> }
        val collector = b.eventCollector!!
        collector.drainEvents()

        game.fireEvent(GameEventShuffle(game.humanPlayer))

        val sh = collector.drainEvents().filterIsInstance<NexusGameEvent.LibraryShuffled>()
        assertEquals(sh.size, 1)
        assertEquals(sh[0].seatId, 1)
    }

    // -- Scry --

    @Test
    fun scryEvent() {
        val (b, game, _) = startWithBoard { _, _, _ -> }
        val collector = b.eventCollector!!
        collector.drainEvents()

        game.fireEvent(GameEventScry(game.humanPlayer, 1, 2))

        val scry = collector.drainEvents().filterIsInstance<NexusGameEvent.Scry>()
        assertEquals(scry.size, 1)
        assertEquals(scry[0].seatId, 1)
        assertEquals(scry[0].topCount, 1)
        assertEquals(scry[0].bottomCount, 2)
    }

    // -- Surveil --

    @Test
    fun surveilEvent() {
        val (b, game, _) = startWithBoard { _, _, _ -> }
        val collector = b.eventCollector!!
        collector.drainEvents()

        game.fireEvent(GameEventSurveil(game.humanPlayer, 1, 3))

        val sv = collector.drainEvents().filterIsInstance<NexusGameEvent.Surveil>()
        assertEquals(sv.size, 1)
        assertEquals(sv[0].seatId, 1)
        assertEquals(sv[0].toLibrary, 1)
        assertEquals(sv[0].toGraveyard, 3)
    }

    // -- CombatEnded --

    @Test
    fun combatEndedEvent() {
        val (b, game, _) = startWithBoard { _, _, _ -> }
        val collector = b.eventCollector!!
        collector.drainEvents()

        game.fireEvent(GameEventCombatEnded(listOf(), listOf()))

        val ce = collector.drainEvents().filterIsInstance<NexusGameEvent.CombatEnded>()
        assertEquals(ce.size, 1)
    }

    // -- AI player events get seatId=2 --

    @Test
    fun aiPlayerGetsSeatId2() {
        val (b, game, _) = startWithBoard { _, _, _ -> }
        val collector = b.eventCollector!!
        collector.drainEvents()

        game.fireEvent(GameEventShuffle(game.aiPlayer))

        val sh = collector.drainEvents().filterIsInstance<NexusGameEvent.LibraryShuffled>()
        assertEquals(sh.size, 1)
        assertEquals(sh[0].seatId, 2)
    }

    // -- P/T cache cleared on zone leave --

    @Test
    fun ptCacheClearedOnLeaveBattlefield() {
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val collector = b.eventCollector!!
        collector.drainEvents()

        val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

        // Establish baseline P/T
        game.fireEvent(GameEventCardStatsChanged(creature))
        collector.drainEvents()

        // Creature leaves battlefield — should clear P/T cache
        val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
        val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
        game.fireEvent(GameEventCardChangeZone(creature, bf, gy))
        collector.drainEvents()

        // Re-enter: P/T event should not compare against old cached value
        val hand = game.humanPlayer.getZone(ZoneType.Hand)
        game.fireEvent(GameEventCardChangeZone(creature, gy, bf))
        game.fireEvent(GameEventCardStatsChanged(creature))
        val pt = collector.drainEvents().filterIsInstance<NexusGameEvent.PowerToughnessChanged>()
        // No prior cache → no delta → no event (same stats as baseline creature)
        assertTrue(pt.isEmpty(), "Re-entered creature with same P/T should not emit delta")
    }
}
