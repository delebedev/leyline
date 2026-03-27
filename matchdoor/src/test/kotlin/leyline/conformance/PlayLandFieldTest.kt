package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Field-level conformance test for play-land protocol messages.
 *
 * Uses [startWithBoard] + synchronous [game.action.moveToPlay] (~0.01s)
 * instead of full game boot (~0.7s). All field checks in one test via
 * [assertSoftly] — reports all failures, not just the first.
 */
class PlayLandFieldTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("play land GSM field conformance") {
            val (b, game, counter) = base.startWithBoard { game, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
            }

            val player = b.getPlayer(SeatId(1))!!
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            val origInstanceId = b.getOrAllocInstanceId(ForgeCardId(land.id)).value
            val cardId = land.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            // Hard gate — if no annotations, nothing else matters
            gsm.annotationsList.shouldNotBeEmpty()
            origInstanceId shouldNotBe newInstanceId

            assertSoftly {
                // annotation IDs: positive, sequential, unique
                val ids = gsm.annotationsList.map { it.id }
                ids shouldBe ids.sorted()
                ids.toSet().size shouldBe ids.size

                // ZoneTransfer: Hand → Battlefield, PlayLand category
                val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
                zt.affectedIdsList.shouldContain(newInstanceId)
                zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
                zt.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                zt.detailString("category") shouldBe "PlayLand"

                // ObjectIdChanged: old → new instanceId
                val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
                oic.affectedIdsList.shouldContain(origInstanceId)
                oic.detailInt("orig_id") shouldBe origInstanceId
                oic.detailInt("new_id") shouldBe newInstanceId

                // UserActionTaken: seat 1 played the land
                val uat = gsm.annotation(AnnotationType.UserActionTaken)
                uat.affectorId.toInt() shouldBe 1
                uat.affectedIdsList.shouldContain(newInstanceId)
                uat.detailInt("actionType") shouldBeGreaterThan 0
                uat.detail("abilityGrpId").shouldNotBeNull()

                // gsId chain: prev < current
                gsm.prevGameStateId shouldBe gsm.gameStateId - 1

                // persistent: EnteredZoneThisTurn on the land
                gsm.persistentAnnotationsCount shouldBeGreaterThan 0
                val enteredZone = gsm.persistentAnnotationsList
                    .firstOrNull { AnnotationType.EnteredZoneThisTurn in it.typeList }
                    .shouldNotBeNull()
                enteredZone.affectedIdsList.shouldContain(newInstanceId)

                // land object: on BF with mana abilities, not in Limbo
                val landObj = gsm.gameObjectsList
                    .firstOrNull { it.instanceId == newInstanceId }
                    .shouldNotBeNull()
                landObj.zoneId shouldBe ZoneIds.BATTLEFIELD
                landObj.uniqueAbilitiesCount shouldBeGreaterThan 0
                gsm.gameObjectsList.filter { it.zoneId == ZoneIds.LIMBO }.shouldBeEmpty()

                // old instanceId retired to Limbo zone, not deleted
                assertLimboContains(gsm, origInstanceId)
                gsm.diffDeletedInstanceIdsList.contains(origInstanceId).shouldBeFalse()
            }
        }

        test("accumulated state after play land") {
            val (b, game, counter) = base.startGameAtMain1()

            val startResult = base.gameStart(game, b, counter)
            val acc = ClientAccumulator()
            acc.seedFull(base.handshakeFull(game, b, counter.currentGsId()))
            acc.processAll(startResult.messages)
            b.snapshotFromGame(game)

            val player = b.getPlayer(SeatId(1)) ?: error("Player 1 not found")
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(ForgeCardId(land.id))
            val cardId = land.id

            base.playLand(b) ?: error("No land in hand at seed 42")
            val postResult = base.postAction(game, b, counter)
            acc.processAll(postResult.messages)
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))

            assertSoftly {
                // New instanceId on BF
                val newObj = acc.objects[newInstanceId.value].shouldNotBeNull()
                newObj.zoneId shouldBe ZoneIds.BATTLEFIELD

                val handZone = acc.zones.values
                    .firstOrNull { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
                    .shouldNotBeNull()
                handZone.objectInstanceIdsList.contains(origInstanceId.value).shouldBeFalse()

                // BF + Limbo zone checks
                val bfZone = acc.zones[ZoneIds.BATTLEFIELD].shouldNotBeNull()
                bfZone.objectInstanceIdsList.shouldContain(newInstanceId.value)

                val limboZone = acc.zones[ZoneIds.LIMBO].shouldNotBeNull()
                limboZone.objectInstanceIdsList.shouldContain(origInstanceId.value)
            }

            acc.assertConsistent("after play land")
        }
    })
