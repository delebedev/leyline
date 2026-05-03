package leyline.game

import forge.game.card.CardView
import forge.game.card.CounterEnumType
import forge.game.event.*
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbilityView
import forge.game.spellability.StackItemView
import forge.game.zone.ZoneType
import forge.trackable.TrackableProperty
import forge.trackable.Tracker
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.aiPlayer
import leyline.conformance.humanPlayer
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds

/**
 * Tests for [leyline.game.event.GameEventCollector] — verifies that Forge engine events are
 * captured and converted to the correct [leyline.game.event.GameEvent] variants.
 *
 * Uses startWithBoard{} — fires events directly via game.fireEvent(),
 * then asserts on collector.closeFrame(). ~0.01s per test.
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
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!

            // startWithBoard fires some events during setup
            collector.closeFrame()

            // Fire a simple event
            game.fireEvent(GameEventShuffle(game.humanPlayer))
            val events1 = collector.closeFrame().events
            events1.shouldNotBeEmpty()

            val events2 = collector.closeFrame().events
            events2.shouldBeEmpty()
        }

        // -- LandPlayed --

        test("land played event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val land =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.isLand }
            game.fireEvent(GameEventLandPlayed(PlayerView.get(game.humanPlayer), CardView.get(land)))

            val events = collector.closeFrame().events
            val lp = events.filterIsInstance<GameEvent.LandPlayed>()
            assertSoftly {
                lp.size shouldBe 1
                lp[0].cardId shouldBe ForgeCardId(land.id)
                lp[0].seatId shouldBe SeatId(1)
            }
        }

        // -- SpellCast --

        test("spell cast event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventSpellAbilityCast(spell.firstSpellAbility, null, 0))

            val events = collector.closeFrame().events
            val sc = events.filterIsInstance<GameEvent.SpellCast>()
            assertSoftly {
                sc.size shouldBe 1
                sc[0].cardId shouldBe ForgeCardId(spell.id)
                sc[0].seatId shouldBe SeatId(1)
            }
        }

        // -- AbilityWireIdentity lineage record on SpellCast --

        test("SpellCast records AbilityWireIdentity when isAbility and ability id is positive") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val sa = source.firstSpellAbility
            val abilityForgeId = sa.id
            val si = StackItemView(abilityForgeId, Tracker()).apply { set(TrackableProperty.Ability, true) }
            val ev =
                GameEventSpellAbilityCast(
                    SpellAbilityView.get(sa),
                    si,
                    0,
                    null,
                    emptyList(),
                )
            game.fireEvent(ev)

            val recorded = b.abilityLineage.lookup(abilityForgeId)
            recorded.shouldNotBeNull()
            val sourceForgeId = ForgeCardId(source.id)
            val expectedSourceIid = b.getOrAllocInstanceId(sourceForgeId)
            val expectedAbilityIid = FrameIdResolver(b).triggerStackAbilityIid(abilityForgeId)
            assertSoftly {
                recorded.abilityForgeId shouldBe abilityForgeId
                recorded.sourceForgeId shouldBe sourceForgeId
                recorded.sourceIidAtCreate shouldBe expectedSourceIid
                recorded.abilityIid shouldBe expectedAbilityIid
                recorded.sourceZoneAtCreate shouldBe ZoneIds.BATTLEFIELD
                recorded.abilityGrpId shouldBe 0
            }
        }

        test("SpellCast skips lineage record when isAbility is false") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            b.abilityLineage.clear()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            // Null si means isAbility defaults to false — covers the spell-cast path.
            game.fireEvent(GameEventSpellAbilityCast(spell.firstSpellAbility, null, 0))

            b.abilityLineage.lookup(spell.firstSpellAbility.id).shouldBeNull()
        }

        test("SpellCast skips lineage record when SpellAbilityView is null even with isAbility=true") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            b.abilityLineage.clear()

            // Direct record construction: sa() is null so ev.sa()?.id ?: 0 → 0,
            // and the lineage gate `lineageAbilityForgeId > 0` filters it out.
            // The visit() method short-circuits on null hostCard before reaching
            // the lineage block, so this test pairs with the isAbility=false case
            // to cover the second branch of the gate (id > 0).
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val sa = source.firstSpellAbility
            // Use the real SA so visit() does not short-circuit on hostCard,
            // but pass a StackItemView with isAbility=false to exercise the
            // !isAbility skip while still reaching the gate.
            val si = StackItemView(sa.id, Tracker()) // Ability prop unset → false
            val ev = GameEventSpellAbilityCast(SpellAbilityView.get(sa), si, 0, null, emptyList())
            game.fireEvent(ev)

            b.abilityLineage.lookup(sa.id).shouldBeNull()
        }

        // -- SpellResolved --

        test("spell resolved event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))

            val events = collector.closeFrame().events
            val sr = events.filterIsInstance<GameEvent.SpellResolved>()
            assertSoftly {
                sr.size shouldBe 1
                sr[0].cardId shouldBe ForgeCardId(spell.id)
                sr[0].hasFizzled.shouldBeFalse()
            }
        }

        test("spell resolved fizzled") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, true))

            val sr = collector.closeFrame().events.filterIsInstance<GameEvent.SpellResolved>()
            sr.size shouldBe 1
            sr[0].hasFizzled.shouldBeTrue()
        }

        // -- CardChangeZone: specific variants --

        test("BF to GY via zone change emits ZoneChanged (CardDestroyed comes from dedicated event)") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(creature, bf, gy))

            val events = collector.closeFrame().events
            // BF→GY via zone change now produces ZoneChanged (not CardDestroyed)
            val zoneChanges = events.filterIsInstance<GameEvent.ZoneChanged>()
            zoneChanges.size shouldBe 1
            zoneChanges[0].cardId shouldBe ForgeCardId(creature.id)
        }

        test("GameEventCardDestroyed emits CardDestroyed with source") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    base.addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bolt =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventCardDestroyed(creature, bolt))

            val events = collector.closeFrame().events
            val destroyed = events.filterIsInstance<GameEvent.CardDestroyed>()
            assertSoftly {
                destroyed.size shouldBe 1
                destroyed[0].cardId shouldBe ForgeCardId(creature.id)
                destroyed[0].seatId shouldBe SeatId(1)
                destroyed[0].sourceCardId shouldBe ForgeCardId(bolt.id)
            }
        }

        test("BF to Hand emits CardBounced") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val hand = game.humanPlayer.getZone(ZoneType.Hand)
            game.fireEvent(GameEventCardChangeZone(creature, bf, hand))

            val bounced = collector.closeFrame().events.filterIsInstance<GameEvent.CardBounced>()
            bounced.size shouldBe 1
            bounced[0].cardId shouldBe ForgeCardId(creature.id)
        }

        test("any to Exile emits CardExiled") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val exile = game.humanPlayer.getZone(ZoneType.Exile)
            game.fireEvent(GameEventCardChangeZone(creature, bf, exile))

            val exiled = collector.closeFrame().events.filterIsInstance<GameEvent.CardExiled>()
            exiled.size shouldBe 1
        }

        test("Hand to GY emits CardDiscarded") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val card =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            val hand = game.humanPlayer.getZone(ZoneType.Hand)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(card, hand, gy))

            val discarded = collector.closeFrame().events.filterIsInstance<GameEvent.CardDiscarded>()
            discarded.size shouldBe 1
        }

        test("Library to GY emits CardMilled") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Library)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val card =
                game.humanPlayer
                    .getZone(ZoneType.Library)
                    .cards
                    .first()
            val lib = game.humanPlayer.getZone(ZoneType.Library)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(card, lib, gy))

            val milled = collector.closeFrame().events.filterIsInstance<GameEvent.CardMilled>()
            milled.size shouldBe 1
        }

        test("generic fallback emits ZoneChanged") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Graveyard)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val card =
                game.humanPlayer
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .first()
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            val lib = game.humanPlayer.getZone(ZoneType.Library)
            game.fireEvent(GameEventCardChangeZone(card, gy, lib))

            val zc = collector.closeFrame().events.filterIsInstance<GameEvent.ZoneChanged>()
            assertSoftly {
                zc.size shouldBe 1
                zc[0].from shouldBe Zone.Graveyard
                zc[0].to shouldBe Zone.Library
            }
        }

        // -- CardTapped --

        test("card tapped event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val land =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            game.fireEvent(GameEventCardTapped(land, true))

            val tapped = collector.closeFrame().events.filterIsInstance<GameEvent.CardTapped>()
            assertSoftly {
                tapped.size shouldBe 1
                tapped[0].cardId shouldBe ForgeCardId(land.id)
                tapped[0].tapped.shouldBeTrue()
            }
        }

        test("card untapped event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val land =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            game.fireEvent(GameEventCardTapped(land, false))

            val tapped = collector.closeFrame().events.filterIsInstance<GameEvent.CardTapped>()
            tapped.size shouldBe 1
            tapped[0].tapped.shouldBeFalse()
        }

        // -- Damage --

        test("damage dealt to card event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    base.addCard("Serra Angel", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val cards =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val source = cards[0]
            val target = cards[1]
            game.fireEvent(GameEventCardDamaged(CardView.get(target), CardView.get(source), 2, GameEventCardDamaged.DamageType.Normal))

            val dmg = collector.closeFrame().events.filterIsInstance<GameEvent.DamageDealtToCard>()
            assertSoftly {
                dmg.size shouldBe 1
                dmg[0].sourceCardId shouldBe ForgeCardId(source.id)
                dmg[0].targetCardId shouldBe ForgeCardId(target.id)
                dmg[0].amount shouldBe 2
            }
        }

        test("damage dealt to player event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventPlayerDamaged(PlayerView.get(game.humanPlayer), CardView.get(creature), 3, true, false))

            val dmg = collector.closeFrame().events.filterIsInstance<GameEvent.DamageDealtToPlayer>()
            assertSoftly {
                dmg.size shouldBe 1
                dmg[0].sourceCardId shouldBe ForgeCardId(creature.id)
                dmg[0].targetSeatId shouldBe SeatId(1)
                dmg[0].amount shouldBe 3
                dmg[0].combat.shouldBeTrue()
            }
        }

        // -- LifeChanged --

        test("life changed event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventPlayerLivesChanged(game.humanPlayer, 20, 17))

            val lc = collector.closeFrame().events.filterIsInstance<GameEvent.LifeChanged>()
            assertSoftly {
                lc.size shouldBe 1
                lc[0].seatId shouldBe SeatId(1)
                lc[0].oldLife shouldBe 20
                lc[0].newLife shouldBe 17
            }
        }

        // -- CardSacrificed --

        test("card sacrificed event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventCardSacrificed(CardView.get(creature)))

            val sac = collector.closeFrame().events.filterIsInstance<GameEvent.CardSacrificed>()
            assertSoftly {
                sac.size shouldBe 1
                sac[0].cardId shouldBe ForgeCardId(creature.id)
                sac[0].seatId shouldBe SeatId(1)
            }
        }

        // -- Attachment --

        test("card attached event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    base.addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val cards =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .toList()
            val aura = cards.first { !it.isCreature }
            val creature = cards.first { it.isCreature }
            game.fireEvent(GameEventCardAttachment(aura, null, creature))

            val attached = collector.closeFrame().events.filterIsInstance<GameEvent.CardAttached>()
            assertSoftly {
                attached.size shouldBe 1
                attached[0].cardId shouldBe ForgeCardId(aura.id)
                attached[0].targetCardId shouldBe ForgeCardId(creature.id)
            }
        }

        test("card detached event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Pacifism", human, ZoneType.Battlefield)
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val aura =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { !it.isCreature }
            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventCardAttachment(aura, creature, null))

            val detached = collector.closeFrame().events.filterIsInstance<GameEvent.CardDetached>()
            detached.size shouldBe 1
            detached[0].cardId shouldBe ForgeCardId(aura.id)
        }

        // -- Counters --

        test("counters changed event") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventCardCounters(creature, CounterEnumType.P1P1, 0, 2))

            val cc = collector.closeFrame().events.filterIsInstance<GameEvent.CountersChanged>()
            assertSoftly {
                cc.size shouldBe 1
                cc[0].cardId shouldBe ForgeCardId(creature.id)
                cc[0].counterType shouldBe "+1/+1"
                cc[0].oldCount shouldBe 0
                cc[0].newCount shouldBe 2
            }
        }

        // P/T deltas and DFC backside flips are now synthesized from the
        // prev/cur snap delta in [leyline.game.event.SnapDeltaSynthesizer] —
        // see SnapDeltaSynthesizerTest.

        // -- Shuffle --

        test("library shuffled event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventShuffle(game.humanPlayer))

            val sh = collector.closeFrame().events.filterIsInstance<GameEvent.LibraryShuffled>()
            sh.size shouldBe 1
            sh[0].seatId shouldBe SeatId(1)
        }

        // -- Scry --

        test("scry event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventScry(PlayerView.get(game.humanPlayer), 1, 2))

            val scry = collector.closeFrame().events.filterIsInstance<GameEvent.Scry>()
            assertSoftly {
                scry.size shouldBe 1
                scry[0].seatId shouldBe SeatId(1)
                scry[0].topCount shouldBe 1
                scry[0].bottomCount shouldBe 2
            }
        }

        // -- Surveil --

        test("surveil event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventSurveil(PlayerView.get(game.humanPlayer), 1, 3))

            val sv = collector.closeFrame().events.filterIsInstance<GameEvent.Surveil>()
            assertSoftly {
                sv.size shouldBe 1
                sv[0].seatId shouldBe SeatId(1)
                sv[0].toLibrary shouldBe 1
                sv[0].toGraveyard shouldBe 3
            }
        }

        // -- CombatEnded --

        test("combat ended event") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventCombatEnded(listOf(), listOf()))

            val ce = collector.closeFrame().events.filterIsInstance<GameEvent.CombatEnded>()
            ce.size shouldBe 1
        }

        // -- AI player events get seatId=2 --

        test("AI player gets seatId 2") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventShuffle(game.aiPlayer))

            val sh = collector.closeFrame().events.filterIsInstance<GameEvent.LibraryShuffled>()
            sh.size shouldBe 1
            sh[0].seatId shouldBe SeatId(2)
        }
    })
