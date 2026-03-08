package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.snapshotFromGame

/**
 * Countered spell conformance: cast creature → counter it (fizzle) → assert
 * Stack→GY with Countered category, not Resolve.
 *
 * Source: recording #7 (22-24-00/engine) T9 grp:91806 Countered.
 * Bug found during triage: SpellResolved fires with hasFizzled=true but
 * categoryFromEvents returns Resolve before checking zone-pair fallback.
 */
class CounteredSpellTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("countered creature goes to graveyard with Countered category") {
            val (b, game, counter) = base.startGameAtMain1()

            // Play land for mana
            base.playLand(b)
            b.snapshotFromGame(game, counter.nextGsId())

            // Cast creature (goes to Stack)
            val player = b.getPlayer(SeatId(1))!!
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val forgeCardId = creature.id

            base.castCreature(b)
            b.snapshotFromGame(game, counter.nextGsId())
            val stackId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value

            // Now counter it: move from Stack to Graveyard directly.
            val stackCard = game.stackZone.cards.firstOrNull { it.id == forgeCardId }
                ?: error("Creature not found on stack (forgeCardId=$forgeCardId)")

            // Counter the spell via game action
            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value

            // Assert: ZoneTransfer annotation with "Countered" category
            val zt = gsm.findZoneTransfer(newId) ?: gsm.findZoneTransfer(stackId)
            zt.shouldNotBeNull()
            zt.category shouldBe "Countered"

            // Creature should be in graveyard
            val gyCards = player.getZone(ZoneType.Graveyard).cards
            (gyCards.any { it.id == forgeCardId }) shouldBe true
        }

        test("fizzled SpellResolved event produces Countered not Resolve") {
            val (b, game, counter) = base.startGameAtMain1()

            base.playLand(b)
            b.snapshotFromGame(game, counter.nextGsId())

            val player = b.getPlayer(SeatId(1))!!
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val forgeCardId = creature.id

            base.castCreature(b)
            b.snapshotFromGame(game, counter.nextGsId())

            val stackCard = game.stackZone.cards.firstOrNull { it.id == forgeCardId }
                ?: error("Creature not found on stack")

            // Manually fire SpellResolved with fizzled=true then move to GY
            val gsm = base.captureAfterAction(b, game, counter) {
                game.fireEvent(forge.game.event.GameEventSpellResolved(stackCard.firstSpellAbility, true))
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value

            val zt = gsm.findZoneTransfer(newId)
            zt.shouldNotBeNull()
            zt.category shouldBe "Countered"
        }
    })
