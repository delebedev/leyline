package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag

/**
 * Spell-forced discard conformance: simulates a spell effect causing
 * the opponent to discard, verifying the Hand->GY annotation uses
 * "Discard" category.
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 */
class SpellForcedDiscardTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("spell forced discard produces Discard category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
                base.addCard("Giant Growth", human, ZoneType.Hand)
            }
            val human = game.humanPlayer
            val cardInHand = human.getZone(ZoneType.Hand).cards.first()
            val forgeCardId = cardInHand.id

            val gsm = base.captureAfterAction(b, game, counter) {
                human.discard(cardInHand, null, false, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId))
            zt.category shouldBe "Discard"
            human.getZone(ZoneType.Graveyard).cards.any { it.id == forgeCardId }.shouldBeTrue()
        }

        test("multiple spell forced discards all produce Discard category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
                base.addCard("Giant Growth", human, ZoneType.Hand)
            }
            val human = game.humanPlayer
            val hand = human.getZone(ZoneType.Hand).cards.toList()
            val card1 = hand[0]
            val card2 = hand[1]

            val gsm = base.captureAfterAction(b, game, counter) {
                human.discard(card1, null, false, AbilityKey.newMap())
                human.discard(card2, null, false, AbilityKey.newMap())
            }

            checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(card1.id))).category shouldBe "Discard"
            checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(card2.id))).category shouldBe "Discard"
        }

        test("discard with spell resolved does not contaminate") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Mind Rot", human, ZoneType.Hand)
                base.addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val human = game.humanPlayer
            val hand = human.getZone(ZoneType.Hand).cards.toList()
            val spellCard = hand[0]
            val discardTarget = hand[1]
            val discardForgeId = discardTarget.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.fireEvent(
                    forge.game.event.GameEventSpellResolved(spellCard.firstSpellAbility, false),
                )
                human.discard(discardTarget, null, false, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(discardForgeId)

            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Discard"
        }
    })
