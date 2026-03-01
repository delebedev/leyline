package leyline.game

import forge.game.card.CounterEnumType
import forge.game.event.*
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.conformance.aiPlayer
import leyline.conformance.humanPlayer

/**
 * Tests for [GameEventCollector] — verifies that Forge engine events are
 * captured and converted to the correct [GameEvent] variants.
 *
 * Uses startWithBoard{} — fires events directly via game.fireEvent(),
 * then asserts on collector.drainEvents(). ~0.01s per test.
 */
class GameEventCollectorTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // -- infrastructure --

        test("collector is wired after wrapGame") {
            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            b.eventCollector.shouldNotBeNull()
        }

        test("drain events returns and clears") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
            }
            val collector = b.eventCollector!!

            // startWithBoard fires some events during setup
            collector.drainEvents()

            // Fire a simple event
            game.fireEvent(GameEventShuffle(game.humanPlayer))
            val events1 = collector.drainEvents()
            events1.shouldNotBeEmpty()

            val events2 = collector.drainEvents()
            events2.shouldBeEmpty()
        }

        // -- LandPlayed --

        test("land played event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val land = game.humanPlayer.getZone(ZoneType.Hand).cards.first { it.isLand }
            game.fireEvent(GameEventLandPlayed(game.humanPlayer, land))

            val events = collector.drainEvents()
            val lp = events.filterIsInstance<GameEvent.LandPlayed>()
            lp.size shouldBe 1
            lp[0].forgeCardId shouldBe land.id
            lp[0].seatId shouldBe 1
        }

        // -- SpellCast --

        test("spell cast event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
            game.fireEvent(GameEventSpellAbilityCast(spell.firstSpellAbility, null, 0))

            val events = collector.drainEvents()
            val sc = events.filterIsInstance<GameEvent.SpellCast>()
            sc.size shouldBe 1
            sc[0].forgeCardId shouldBe spell.id
            sc[0].seatId shouldBe 1
        }

        // -- SpellResolved --

        test("spell resolved event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
            game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))

            val events = collector.drainEvents()
            val sr = events.filterIsInstance<GameEvent.SpellResolved>()
            sr.size shouldBe 1
            sr[0].forgeCardId shouldBe spell.id
            sr[0].hasFizzled.shouldBeFalse()
        }

        test("spell resolved fizzled") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
            game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, true))

            val sr = collector.drainEvents().filterIsInstance<GameEvent.SpellResolved>()
            sr.size shouldBe 1
            sr[0].hasFizzled.shouldBeTrue()
        }

        // -- CardChangeZone: specific variants --

        test("BF to GY emits CardDestroyed") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(creature, bf, gy))

            val events = collector.drainEvents()
            val destroyed = events.filterIsInstance<GameEvent.CardDestroyed>()
            destroyed.size shouldBe 1
            destroyed[0].forgeCardId shouldBe creature.id
            destroyed[0].seatId shouldBe 1
        }

        test("BF to Hand emits CardBounced") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val hand = game.humanPlayer.getZone(ZoneType.Hand)
            game.fireEvent(GameEventCardChangeZone(creature, bf, hand))

            val bounced = collector.drainEvents().filterIsInstance<GameEvent.CardBounced>()
            bounced.size shouldBe 1
            bounced[0].forgeCardId shouldBe creature.id
        }

        test("any to Exile emits CardExiled") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val exile = game.humanPlayer.getZone(ZoneType.Exile)
            game.fireEvent(GameEventCardChangeZone(creature, bf, exile))

            val exiled = collector.drainEvents().filterIsInstance<GameEvent.CardExiled>()
            exiled.size shouldBe 1
        }

        test("Hand to GY emits CardDiscarded") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val card = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
            val hand = game.humanPlayer.getZone(ZoneType.Hand)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(card, hand, gy))

            val discarded = collector.drainEvents().filterIsInstance<GameEvent.CardDiscarded>()
            discarded.size shouldBe 1
        }

        test("Library to GY emits CardMilled") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Library)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val card = game.humanPlayer.getZone(ZoneType.Library).cards.first()
            val lib = game.humanPlayer.getZone(ZoneType.Library)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(card, lib, gy))

            val milled = collector.drainEvents().filterIsInstance<GameEvent.CardMilled>()
            milled.size shouldBe 1
        }

        test("generic fallback emits ZoneChanged") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Graveyard)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val card = game.humanPlayer.getZone(ZoneType.Graveyard).cards.first()
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            val lib = game.humanPlayer.getZone(ZoneType.Library)
            game.fireEvent(GameEventCardChangeZone(card, gy, lib))

            val zc = collector.drainEvents().filterIsInstance<GameEvent.ZoneChanged>()
            zc.size shouldBe 1
            zc[0].from shouldBe ZoneType.Graveyard
            zc[0].to shouldBe ZoneType.Library
        }

        // -- CardTapped --

        test("card tapped event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val land = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first()
            game.fireEvent(GameEventCardTapped(land, true))

            val tapped = collector.drainEvents().filterIsInstance<GameEvent.CardTapped>()
            tapped.size shouldBe 1
            tapped[0].forgeCardId shouldBe land.id
            tapped[0].tapped.shouldBeTrue()
        }

        test("card untapped event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val land = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first()
            game.fireEvent(GameEventCardTapped(land, false))

            val tapped = collector.drainEvents().filterIsInstance<GameEvent.CardTapped>()
            tapped.size shouldBe 1
            tapped[0].tapped.shouldBeFalse()
        }

        // -- Damage --

        test("damage dealt to card event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Serra Angel", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val cards = game.humanPlayer.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
            val source = cards[0]
            val target = cards[1]
            game.fireEvent(GameEventCardDamaged(target, source, 2, GameEventCardDamaged.DamageType.Normal))

            val dmg = collector.drainEvents().filterIsInstance<GameEvent.DamageDealtToCard>()
            dmg.size shouldBe 1
            dmg[0].sourceForgeId shouldBe source.id
            dmg[0].targetForgeId shouldBe target.id
            dmg[0].amount shouldBe 2
        }

        test("damage dealt to player event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            game.fireEvent(GameEventPlayerDamaged(game.humanPlayer, creature, 3, true, false))

            val dmg = collector.drainEvents().filterIsInstance<GameEvent.DamageDealtToPlayer>()
            dmg.size shouldBe 1
            dmg[0].sourceForgeId shouldBe creature.id
            dmg[0].targetSeatId shouldBe 1
            dmg[0].amount shouldBe 3
            dmg[0].combat.shouldBeTrue()
        }

        // -- LifeChanged --

        test("life changed event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.drainEvents()

            game.fireEvent(GameEventPlayerLivesChanged(game.humanPlayer, 20, 17))

            val lc = collector.drainEvents().filterIsInstance<GameEvent.LifeChanged>()
            lc.size shouldBe 1
            lc[0].seatId shouldBe 1
            lc[0].oldLife shouldBe 20
            lc[0].newLife shouldBe 17
        }

        // -- CardSacrificed --

        test("card sacrificed event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            game.fireEvent(GameEventCardSacrificed(creature))

            val sac = collector.drainEvents().filterIsInstance<GameEvent.CardSacrificed>()
            sac.size shouldBe 1
            sac[0].forgeCardId shouldBe creature.id
            sac[0].seatId shouldBe 1
        }

        // -- Attachment --

        test("card attached event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Holy Strength", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val cards = game.humanPlayer.getZone(ZoneType.Battlefield).cards.toList()
            val aura = cards.first { !it.isCreature }
            val creature = cards.first { it.isCreature }
            game.fireEvent(GameEventCardAttachment(aura, null, creature))

            val attached = collector.drainEvents().filterIsInstance<GameEvent.CardAttached>()
            attached.size shouldBe 1
            attached[0].forgeCardId shouldBe aura.id
            attached[0].targetForgeId shouldBe creature.id
        }

        test("card detached event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Holy Strength", human, ZoneType.Battlefield)
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val aura = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { !it.isCreature }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            game.fireEvent(GameEventCardAttachment(aura, creature, null))

            val detached = collector.drainEvents().filterIsInstance<GameEvent.CardDetached>()
            detached.size shouldBe 1
            detached[0].forgeCardId shouldBe aura.id
        }

        // -- Counters --

        test("counters changed event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            game.fireEvent(GameEventCardCounters(creature, CounterEnumType.P1P1, 0, 2))

            val cc = collector.drainEvents().filterIsInstance<GameEvent.CountersChanged>()
            cc.size shouldBe 1
            cc[0].forgeCardId shouldBe creature.id
            cc[0].counterType shouldBe "+1/+1"
            cc[0].oldCount shouldBe 0
            cc[0].newCount shouldBe 2
        }

        // -- P/T changed --

        test("power toughness changed event") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            // First event establishes baseline (no prior cached P/T -> no delta)
            game.fireEvent(GameEventCardStatsChanged(creature))
            collector.drainEvents()

            // Pump the creature, fire again
            creature.setBasePower(creature.getNetPower() + 2)
            game.fireEvent(GameEventCardStatsChanged(creature))

            val pt = collector.drainEvents().filterIsInstance<GameEvent.PowerToughnessChanged>()
            pt.size shouldBe 1
            pt[0].forgeCardId shouldBe creature.id
            pt[0].newPower shouldBe creature.getNetPower()
        }

        test("power toughness unchanged - no duplicate") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            // Fire twice with same stats -- second should not emit
            game.fireEvent(GameEventCardStatsChanged(creature))
            collector.drainEvents()
            game.fireEvent(GameEventCardStatsChanged(creature))

            val pt = collector.drainEvents().filterIsInstance<GameEvent.PowerToughnessChanged>()
            pt.shouldBeEmpty()
        }

        // -- Shuffle --

        test("library shuffled event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.drainEvents()

            game.fireEvent(GameEventShuffle(game.humanPlayer))

            val sh = collector.drainEvents().filterIsInstance<GameEvent.LibraryShuffled>()
            sh.size shouldBe 1
            sh[0].seatId shouldBe 1
        }

        // -- Scry --

        test("scry event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.drainEvents()

            game.fireEvent(GameEventScry(game.humanPlayer, 1, 2))

            val scry = collector.drainEvents().filterIsInstance<GameEvent.Scry>()
            scry.size shouldBe 1
            scry[0].seatId shouldBe 1
            scry[0].topCount shouldBe 1
            scry[0].bottomCount shouldBe 2
        }

        // -- Surveil --

        test("surveil event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.drainEvents()

            game.fireEvent(GameEventSurveil(game.humanPlayer, 1, 3))

            val sv = collector.drainEvents().filterIsInstance<GameEvent.Surveil>()
            sv.size shouldBe 1
            sv[0].seatId shouldBe 1
            sv[0].toLibrary shouldBe 1
            sv[0].toGraveyard shouldBe 3
        }

        // -- CombatEnded --

        test("combat ended event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.drainEvents()

            game.fireEvent(GameEventCombatEnded(listOf(), listOf()))

            val ce = collector.drainEvents().filterIsInstance<GameEvent.CombatEnded>()
            ce.size shouldBe 1
        }

        // -- AI player events get seatId=2 --

        test("AI player gets seatId 2") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.drainEvents()

            game.fireEvent(GameEventShuffle(game.aiPlayer))

            val sh = collector.drainEvents().filterIsInstance<GameEvent.LibraryShuffled>()
            sh.size shouldBe 1
            sh[0].seatId shouldBe 2
        }

        // -- P/T cache cleared on zone leave --

        test("P/T cache cleared on leave battlefield") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            // Establish baseline P/T
            game.fireEvent(GameEventCardStatsChanged(creature))
            collector.drainEvents()

            // Creature leaves battlefield -- should clear P/T cache
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(creature, bf, gy))
            collector.drainEvents()

            // Re-enter: P/T event should not compare against old cached value
            game.fireEvent(GameEventCardChangeZone(creature, gy, bf))
            game.fireEvent(GameEventCardStatsChanged(creature))
            val pt = collector.drainEvents().filterIsInstance<GameEvent.PowerToughnessChanged>()
            pt.shouldBeEmpty()
        }
    })
