package leyline.conformance

import forge.game.Game
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.MessageCounter
import leyline.game.snapshotFromGame

/**
 * Countered spell conformance: cast creature → counter it → assert
 * Stack→GY with Countered category, not Resolve.
 *
 * Source: recording #7 (22-24-00/engine) T9 grp:91806 Countered.
 * Bug found during triage: SpellResolved fires with hasFizzled=true but
 * categoryFromEvents returned Resolve before checking zone-pair fallback.
 */
class CounteredSpellTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        /**
         * Shared setup: play land, cast creature (→ Stack), snapshot.
         * Returns the stack Card and its cardId.
         */
        fun castToStack(): Triple<Triple<GameBridge, Game, MessageCounter>, Card, Int> {
            val setup = base.startGameAtMain1()
            val (b, game, counter) = setup

            base.playLand(b)
            b.snapshotFromGame(game, counter.nextGsId())

            val player = b.getPlayer(SeatId(1))!!
            val creature = player.getZone(ZoneType.Hand).cards.first { it.isCreature }
            val cardId = creature.id

            base.castCreature(b)
            b.snapshotFromGame(game, counter.nextGsId())

            val stackCard = game.stackZone.cards.first { it.id == cardId }
            return Triple(setup, stackCard, cardId)
        }

        test("countered creature goes to graveyard with Countered category") {
            val (setup, stackCard, cardId) = castToStack()
            val (b, game, counter) = setup

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for countered spell" }
            zt.category shouldBe "Countered"
        }

        test("fizzled SpellResolved event produces Countered not Resolve") {
            val (setup, stackCard, cardId) = castToStack()
            val (b, game, counter) = setup

            val gsm = base.captureAfterAction(b, game, counter) {
                game.fireEvent(forge.game.event.GameEventSpellResolved(stackCard.firstSpellAbility, true))
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for fizzled spell" }
            zt.category shouldBe "Countered"
        }
    })
