package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

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
            val forgeCardId = land.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value

            // Hard gate — if no annotations, nothing else matters
            gsm.annotationsList.shouldNotBeEmpty()
            origInstanceId shouldNotBe newInstanceId

            assertSoftly {
                // annotation IDs sequential
                val ids = gsm.annotationsList.map { it.id }
                ids.all { it > 0 }.shouldBeTrue()
                ids shouldBe ids.sorted()
                ids.toSet().size shouldBe ids.size

                // ZoneTransfer annotation
                val zt = gsm.annotationOrNull(AnnotationType.ZoneTransfer_af5a)
                zt.shouldNotBeNull()
                zt!!
                zt.affectedIdsList.shouldContain(newInstanceId)
                val zoneSrc = zt.detail("zone_src")
                zoneSrc.shouldNotBeNull()
                zoneSrc!!.type shouldBe KeyValuePairValueType.Int32
                zoneSrc.getValueInt32(0) shouldBe ZoneIds.P1_HAND
                val zoneDest = zt.detail("zone_dest")
                zoneDest.shouldNotBeNull()
                zoneDest!!.type shouldBe KeyValuePairValueType.Int32
                zoneDest.getValueInt32(0) shouldBe ZoneIds.BATTLEFIELD
                val category = zt.detail("category")
                category.shouldNotBeNull()
                @Suppress("EnumValuesSoftDeprecate")
                category!!.type shouldBe KeyValuePairValueType.String
                category.getValueString(0) shouldBe "PlayLand"

                // ObjectIdChanged
                val oic = gsm.annotationOrNull(AnnotationType.ObjectIdChanged)
                oic.shouldNotBeNull()
                oic!!
                oic.affectedIdsList.shouldContain(origInstanceId)
                val origIdDetail = oic.detail("orig_id")
                origIdDetail.shouldNotBeNull()
                origIdDetail!!.type shouldBe KeyValuePairValueType.Int32
                origIdDetail.getValueInt32(0) shouldBe origInstanceId
                val newIdDetail = oic.detail("new_id")
                newIdDetail.shouldNotBeNull()
                newIdDetail!!.type shouldBe KeyValuePairValueType.Int32
                newIdDetail.getValueInt32(0) shouldBe newInstanceId
                origInstanceId shouldNotBe newInstanceId

                // UserActionTaken
                val uat = gsm.annotationOrNull(AnnotationType.UserActionTaken)
                uat.shouldNotBeNull()
                uat!!
                uat.affectorId.toInt() shouldBe 1
                uat.affectedIdsList.shouldContain(newInstanceId)
                val actionType = uat.detail("actionType")
                actionType.shouldNotBeNull()
                actionType!!.type shouldBe KeyValuePairValueType.Int32
                (actionType.valueInt32Count > 0).shouldBeTrue()
                val abilityGrpId = uat.detail("abilityGrpId")
                abilityGrpId.shouldNotBeNull()
                abilityGrpId!!.type shouldBe KeyValuePairValueType.Int32

                // prevGameStateId
                gsm.prevGameStateId.toInt() shouldNotBe 0
                (gsm.prevGameStateId < gsm.gameStateId).shouldBeTrue()

                // persistentAnnotations
                (gsm.persistentAnnotationsCount > 0).shouldBeTrue()
                val enteredZone = gsm.persistentAnnotationsList.firstOrNull {
                    AnnotationType.EnteredZoneThisTurn in it.typeList
                }
                enteredZone.shouldNotBeNull()
                enteredZone!!.affectedIdsList.shouldContain(newInstanceId)

                // land object on battlefield with abilities
                val landObj = gsm.gameObjectsList.firstOrNull { it.instanceId == newInstanceId }
                landObj.shouldNotBeNull()
                landObj!!.zoneId shouldBe ZoneIds.BATTLEFIELD
                landObj.uniqueAbilitiesCount shouldBeGreaterThan 0

                // no Limbo objects in diff
                gsm.gameObjectsList.filter { it.zoneId == ZoneIds.LIMBO }.shouldBeEmpty()

                // old instance retired to Limbo zone
                assertLimboContains(gsm, origInstanceId)
                val newObj = gsm.gameObjectsList.firstOrNull { it.instanceId == newInstanceId }
                newObj.shouldNotBeNull()
                newObj!!.zoneId shouldBe ZoneIds.BATTLEFIELD
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
            val forgeCardId = land.id

            base.playLand(b) ?: error("No land in hand at seed 42")
            val postResult = base.postAction(game, b, counter)
            acc.processAll(postResult.messages)
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            assertSoftly {
                // New instanceId on BF
                val newObj = acc.objects[newInstanceId.value]
                newObj.shouldNotBeNull()
                newObj!!.zoneId shouldBe ZoneIds.BATTLEFIELD

                val handZone = acc.zones.values.firstOrNull {
                    it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Hand && it.ownerSeatId == 1
                }
                handZone.shouldNotBeNull()
                handZone!!.objectInstanceIdsList.contains(origInstanceId.value).shouldBeFalse()

                // BF + Limbo zone checks
                val bfZone = acc.zones[ZoneIds.BATTLEFIELD]
                bfZone.shouldNotBeNull()
                bfZone!!.objectInstanceIdsList.shouldContain(newInstanceId.value)

                val limboZone = acc.zones[ZoneIds.LIMBO]
                limboZone.shouldNotBeNull()
                limboZone!!.objectInstanceIdsList.shouldContain(origInstanceId.value)
            }

            acc.assertConsistent("after play land")
        }
    })
