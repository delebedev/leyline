package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId

/**
 * Removal spell flow conformance: simulates removal effects resolving
 * and verifies zone transition annotations use correct categories.
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 */
class RemovalSpellFlowTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("bounce spell resolution produces Bounce category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToHand(creature, null)
            }

            val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value))
            zt.category shouldBe "Bounce"
        }

        test("destroy spell resolution produces Destroy category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.destroy(creature, null, false, AbilityKey.newMap())
            }

            val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value))
            zt.category shouldBe "Destroy"
        }

        test("exile spell resolution produces Exile category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.exile(creature, null, AbilityKey.newMap())
            }

            val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value))
            zt.category shouldBe "Exile"
        }

        test("spell resolved does not contaminate target category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Swords to Plowshares", human, ZoneType.Hand)
            }
            val human = game.humanPlayer
            val creature = human.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val spellCard = human.getZone(ZoneType.Hand).cards.first()
            val creatureForgeId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.fireEvent(
                    forge.game.event.GameEventSpellResolved(spellCard.firstSpellAbility, false),
                )
                game.action.exile(creature, null, AbilityKey.newMap())
            }

            val zt = checkNotNull(gsm.findZoneTransfer(b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value))
            zt.category shouldBe "Exile"
        }
    })
