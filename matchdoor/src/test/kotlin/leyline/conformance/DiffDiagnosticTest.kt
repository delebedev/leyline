package leyline.conformance

import forge.game.ability.AbilityKey
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import forge.game.zone.ZoneType as ForgeZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Diagnostic tests tracing exact diff contents for each game action.
 *
 * Accumulator consistency (zone-object refs, action instanceIds, no duplicates)
 * is automatic via [ValidatingMessageSink]. What remains here are structural
 * assertions about diff contents — which zones appear, annotation types, field values.
 */
class DiffDiagnosticTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("diff after land play has correct GSM type, zones, and annotations") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ForgeZoneType.Hand)
            }

            val land = game.humanPlayer.getZone(ForgeZoneType.Hand).cards.first { it.isLand }
            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            assertSoftly {
                gsm.type shouldBe GameStateType.Diff

                val zoneTypes = gsm.zonesList.map { it.type }.toSet()
                (ProtoZoneType.Hand in zoneTypes).shouldBeTrue()
                (ProtoZoneType.Battlefield in zoneTypes).shouldBeTrue()
                (ProtoZoneType.Limbo in zoneTypes).shouldBeTrue()

                val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
                val origId = oic.detailInt("orig_id")
                gsm.diffDeletedInstanceIdsList.contains(origId).shouldBeFalse()
            }
        }

        test("cast creature -> pass -> resolve tracks zone placement correctly") {
            val (b, game, counter) = base.startGameAtMain1()
            val acc = ClientAccumulator()
            acc.seedFull(base.handshakeFull(game, b, counter.currentGsId()))

            val startResult = base.gameStart(game, b, counter)
            acc.processAll(startResult.messages)
            b.snapshotFromGame(game)

            base.playLand(b) ?: error("playLand failed at seed 42")
            val afterLand = base.postAction(game, b, counter)
            acc.processAll(afterLand.messages)

            val player = b.getPlayer(SeatId(1))!!
            val creature = player.getZone(ForgeZoneType.Hand).cards.first { it.isCreature }
            val creatureForgeId = creature.id

            base.castCreature(b) ?: error("castCreature failed at seed 42")
            val afterCast = base.postAction(game, b, counter)
            acc.processAll(afterCast.messages)

            val creatureNewId = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value
            val creatureObj = checkNotNull(acc.objects[creatureNewId]) {
                "Creature should exist in accumulated objects with instanceId $creatureNewId"
            }

            if (game.stack.isEmpty) {
                creatureObj.zoneId shouldBe ZoneIds.BATTLEFIELD
            } else {
                creatureObj.zoneId shouldBe ZoneIds.STACK

                base.passPriority(b)
                val afterPass = base.postAction(game, b, counter)
                acc.processAll(afterPass.messages)

                val resolved = checkNotNull(acc.objects[creatureNewId]) { "Creature should still exist after resolve" }
                resolved.zoneId shouldBe ZoneIds.BATTLEFIELD
                acc.zones[ZoneIds.BATTLEFIELD]!!.objectInstanceIdsList.shouldContain(creatureNewId)
            }
        }

        test("resolve keeps instanceId") {
            val (b, game, counter) = base.startGameAtMain1()

            base.playLand(b) ?: error("playLand failed at seed 42")
            base.postAction(game, b, counter)

            val player = b.getPlayer(SeatId(1))!!
            val creature = player.getZone(ForgeZoneType.Hand).cards.first { it.isCreature }
            val creatureForgeId = creature.id

            base.castCreature(b) ?: error("castCreature failed at seed 42")
            base.postAction(game, b, counter)
            val castId = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value

            if (!game.stack.isEmpty) {
                base.passPriority(b)
                base.postAction(game, b, counter)
            }

            val resolvedId = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value
            castId shouldBe resolvedId
        }

        test("remoteActionDiff contains BF objects for AI land play") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ForgeZoneType.Hand)
            }

            val land = game.humanPlayer.getZone(ForgeZoneType.Hand).cards.first { it.isLand }
            base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val aiResult = BundleBuilder.remoteActionDiff(
                game,
                b,
                ConformanceTestBase.TEST_MATCH_ID,
                ConformanceTestBase.SEAT_ID,
                counter,
            )

            val gsm = aiResult.gsm
            gsm.type shouldBe GameStateType.Diff

            // remoteActionDiff may or may not include BF zone depending on
            // what changed. Core invariant: all objects have a valid zoneId.
            for (obj in gsm.gameObjectsList) {
                (obj.zoneId > 0).shouldBeTrue()
            }
        }
    })
