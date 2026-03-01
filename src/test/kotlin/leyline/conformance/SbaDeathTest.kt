package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * SBA (state-based action) death conformance: creatures dying to zero
 * toughness, lethal damage, and deathtouch damage should all produce
 * ZoneTransfer annotations with category "Destroy".
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 * checkStateEffects(true) triggers SBAs inline on the test thread.
 */
class SbaDeathTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("zero toughness creature dies to SBA") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val forgeCardId = creature.id
            val origId = b.getOrAllocInstanceId(forgeCardId)

            val gsm = base.captureAfterAction(b, game, counter, checkSba = true) {
                creature.baseToughness = 0
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(origId))
            zt.category shouldBe "Destroy"
            human.getZone(ZoneType.Graveyard).cards.any { it.id == forgeCardId }.shouldBeTrue()
        }

        test("lethal damage creature dies to SBA") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val forgeCardId = creature.id
            val origId = b.getOrAllocInstanceId(forgeCardId)

            val gsm = base.captureAfterAction(b, game, counter, checkSba = true) {
                creature.damage = creature.netToughness
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(origId))
            zt.category shouldBe "Destroy"
        }

        test("deathtouch damage creature dies to SBA") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val forgeCardId = creature.id
            val origId = b.getOrAllocInstanceId(forgeCardId)

            val gsm = base.captureAfterAction(b, game, counter, checkSba = true) {
                creature.damage = 1
                creature.setHasBeenDealtDeathtouchDamage(true)
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(origId))
            zt.category shouldBe "Destroy"
        }
    })
