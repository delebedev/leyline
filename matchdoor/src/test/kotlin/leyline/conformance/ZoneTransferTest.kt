package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.card.CardView
import forge.game.card.CounterEnumType
import forge.game.event.GameEventSpellResolved
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Zone transfer subsystem tests — every zone pair the Arena client expects.
 *
 * Covers: Destroy, Sacrifice, Exile, Bounce, Draw, Discard, Mill, Return,
 * SBA death paths, spell-forced discard, counter annotations, shuffle suppression.
 *
 * For PlayLand (Hand→BF), see LandManaTest.
 * For CastSpell/Resolve/Countered (Hand→Stack, Stack→BF/GY), see StackCastResolveTest.
 */
class ZoneTransferTest :
    SubsystemTest({

        // ===================================================================
        // Battlefield exits
        // ===================================================================

        test("Battlefield → Graveyard (Destroy)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                destroy(card, g)
            }
            val zt = checkNotNull(gsm.findZoneTransfer(newId))
            assertSoftly {
                zt.category shouldBe "Destroy"
                zt.zoneSrc shouldBe ZoneIds.BATTLEFIELD
                zt.zoneDest shouldBe ZoneIds.P1_GRAVEYARD
            }
            gsm.hasEnteredZoneThisTurn(newId).shouldBeFalse()
        }

        test("Battlefield → Graveyard (Sacrifice)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                g.fireEvent(forge.game.event.GameEventCardSacrificed(CardView.get(card)))
                g.action.moveToGraveyard(card, null)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Sacrifice"
        }

        test("Battlefield → Exile") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                exile(card, g)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Exile"
        }

        test("Battlefield → Hand (Bounce)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                g.action.moveToHand(card, null)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Bounce"
        }

        // ===================================================================
        // Library exits
        // ===================================================================

        test("Library → Hand (Draw)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Forest", human, ZoneType.Library)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Forest") { _, g ->
                g.humanPlayer.drawCard()
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Draw"
        }

        test("Library → Graveyard (Mill)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Forest", human, ZoneType.Library)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Forest") { card, g ->
                g.action.moveToGraveyard(card, null)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Mill"
        }

        test("Library → Exile") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Forest", human, ZoneType.Library)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Forest") { card, g ->
                exile(card, g)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Exile"
        }

        // ===================================================================
        // Hand exits
        // ===================================================================

        test("Hand → Graveyard (Discard)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Lightning Bolt") { card, g ->
                g.humanPlayer.discard(card, null, false, AbilityKey.newMap())
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Discard"
            game.humanPlayer.getZone(ZoneType.Graveyard).cards.any { it.name == "Lightning Bolt" }.shouldBeTrue()
        }

        test("Hand → Exile") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Forest", human, ZoneType.Hand)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Forest") { card, g ->
                exile(card, g)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Exile"
        }

        // ===================================================================
        // Return paths
        // ===================================================================

        test("Exile → Battlefield (Return + EnteredZoneThisTurn)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Exile)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                moveToBattlefield(card, g)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Return"
            gsm.hasEnteredZoneThisTurn(newId).shouldBeTrue()
        }

        test("Graveyard → Battlefield (Return + EnteredZoneThisTurn)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                moveToBattlefield(card, g)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Return"
            gsm.hasEnteredZoneThisTurn(newId).shouldBeTrue()
        }

        test("Graveyard → Hand (Return)") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
                g.action.moveToHand(card, null)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Return"
        }

        // ===================================================================
        // SBA death paths
        // ===================================================================

        test("SBA: zero toughness → Destroy") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears", checkSba = true) { card, _ ->
                card.baseToughness = 0
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Destroy"
        }

        test("SBA: lethal damage → Destroy") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears", checkSba = true) { card, _ ->
                card.damage = card.netToughness
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Destroy"
        }

        test("SBA: deathtouch damage → Destroy") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears", checkSba = true) { card, _ ->
                card.damage = 1
                card.setHasBeenDealtDeathtouchDamage(true)
            }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Destroy"
        }

        // ===================================================================
        // Multi-card & contamination
        // ===================================================================

        test("multiple discards all produce Discard category") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Lightning Bolt", human, ZoneType.Hand)
                addCard("Giant Growth", human, ZoneType.Hand)
            }
            val hand = game.humanPlayer.getZone(ZoneType.Hand).cards.toList()

            val gsm = capture(b, game, counter) {
                for (card in hand) game.humanPlayer.discard(card, null, false, AbilityKey.newMap())
            }

            for (card in hand) {
                checkNotNull(gsm.findZoneTransfer(b.instanceId(card.id))).category shouldBe "Discard"
            }
        }

        test("SpellResolved does not contaminate exile category") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
                addCard("Swords to Plowshares", human, ZoneType.Hand)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val spell = game.humanPlayer.getZone(ZoneType.Hand).cards.first()

            val gsm = capture(b, game, counter) {
                game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))
                exile(creature, game)
            }

            checkNotNull(gsm.findZoneTransfer(b.instanceId(creature.id))).category shouldBe "Exile"
        }

        test("SpellResolved does not contaminate discard category") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Mind Rot", human, ZoneType.Hand)
                addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val hand = game.humanPlayer.getZone(ZoneType.Hand).cards.toList()
            val spell = hand[0]
            val target = hand[1]

            val gsm = capture(b, game, counter) {
                game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))
                game.humanPlayer.discard(target, null, false, AbilityKey.newMap())
            }

            checkNotNull(gsm.findZoneTransfer(b.instanceId(target.id))).category shouldBe "Discard"
        }

        // ===================================================================
        // Counter annotations
        // ===================================================================

        test("counter added — type and amount") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            val gsm = capture(b, game, counter) {
                creature.addCounterInternal(CounterEnumType.P1P1, 2, game.humanPlayer, true, null, AbilityKey.newMap())
            }

            val ann = gsm.annotation(AnnotationType.CounterAdded)
            ann.detailString("counter_type") shouldBe "+1/+1"
            ann.detailInt("transaction_amount") shouldBe 2
        }

        test("counter removed — amount") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            creature.addCounterInternal(CounterEnumType.P1P1, 3, game.humanPlayer, true, null, AbilityKey.newMap())
            b.snapshotFromGame(game, counter.currentGsId())
            b.drainEvents()

            val gsm = capture(b, game, counter) {
                creature.subtractCounter(CounterEnumType.P1P1, 2, game.humanPlayer)
            }

            gsm.annotation(AnnotationType.CounterRemoved).detailInt("transaction_amount") shouldBe 2
        }

        // ===================================================================
        // Shuffle
        // ===================================================================

        test("shuffle — annotation suppressed") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Forest", human, ZoneType.Library)
                addCard("Forest", human, ZoneType.Library)
            }

            val gsm = capture(b, game, counter) { game.humanPlayer.shuffle(null) }

            gsm.annotationOrNull(AnnotationType.Shuffle).shouldBeNull()
        }
    })
