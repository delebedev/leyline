package leyline.mechanics.omen

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.InstanceId
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.allGameObjects
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class OmenLifecycleTest :
    SessionTest({
        fun AnnotationInfo.isType(type: AnnotationType): Boolean = type in typeList

        val omenPuzzle =
            """
            [metadata]
            Name:Omen lifecycle
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Cast either face of Riling Dawnbreaker.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=4

            humanhand=Riling Dawnbreaker
            humanbattlefield=Plains;Plains;Plains;Plains;Plains
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        session("Riling Dawnbreaker Omen face follows hand stack library lifecycle", puzzle = omenPuzzle) {
            val handParentIid = human.hand.iid("Riling Dawnbreaker")
            val handCompanion =
                accumulator.objects.values.single {
                    it.type == GameObjectType.Omen_a4aa && it.zoneId == ZoneIds.P1_HAND
                }
            accumulator.zones
                .getValue(ZoneIds.P1_HAND)
                .objectInstanceIdsList shouldNotContain handCompanion.instanceId
            val omenAction =
                allMessages
                    .asReversed()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastOmen && it.instanceId == handParentIid }

            val lifecycleStart = messageSnapshot()
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.CastOmen
                        instanceId = omenAction.instanceId
                    },
                ),
            )
            drainSink()

            val castMessages = messagesSince(lifecycleStart)
            val stackCard =
                castMessages.allGameObjects().single {
                    it.type == GameObjectType.Card && it.zoneId == ZoneIds.STACK && it.grpId == 95537
                }
            val stackCompanion =
                castMessages.allGameObjects().single {
                    it.type == GameObjectType.Omen_a4aa && it.zoneId == ZoneIds.STACK
                }
            val castAnnotations = castMessages.allAnnotations()
            val castObjectIdChanged =
                castAnnotations.single {
                    it.isType(AnnotationType.ObjectIdChanged) && it.detailInt("orig_id") == handParentIid
                }
            val castTransfer =
                castAnnotations.single {
                    it.isType(AnnotationType.ZoneTransfer_af5a) && it.detailString("category") == "CastSpell"
                }
            val acceptedOmen =
                castAnnotations.single {
                    it.isType(AnnotationType.UserActionTaken) && it.detailInt("actionType") == ActionType.CastOmen.number
                }

            assertSoftly {
                stackCard.instanceId shouldNotBe handParentIid
                stackCompanion.parentId shouldBe stackCard.instanceId
                stackCompanion.grpId shouldBe 95537
                stackCompanion.instanceId shouldNotBe handCompanion.instanceId
                castMessages.gameStateMessages().flatMap { it.diffDeletedInstanceIdsList } shouldContain handCompanion.instanceId
                castMessages
                    .gameStateMessages()
                    .flatMap { it.zonesList }
                    .first { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList shouldNotContain stackCompanion.instanceId
                castAnnotations.indexOf(castObjectIdChanged) shouldBe 0
                castAnnotations.indexOf(castObjectIdChanged) shouldBeLessThan castAnnotations.indexOf(castTransfer)
                acceptedOmen.detailInt("abilityGrpId") shouldBe 0
            }

            passUntil(maxPasses = 15) {
                human.getZone(ZoneType.Library).cards.any { it.name == "Riling Dawnbreaker" } &&
                    human.getZone(ZoneType.Battlefield).cards.any { it.isToken && "Soldier" in it.name }
            }.shouldBeTrue()

            val lifecycleMessages = messagesSince(lifecycleStart)
            val lifecycleObjects = lifecycleMessages.allGameObjects()
            val libraryParent =
                lifecycleObjects.singleOrNull {
                    it.type == GameObjectType.Card && it.zoneId == ZoneIds.P1_LIBRARY && it.grpId == 95536
                } ?: error("No library parent; objects=${lifecycleObjects.map { Triple(it.instanceId, it.grpId, it.zoneId) }}")
            val libraryCompanion =
                lifecycleObjects.singleOrNull {
                    it.type == GameObjectType.Omen_a4aa && it.zoneId == ZoneIds.P1_LIBRARY
                } ?: error("No library companion; objects=${lifecycleObjects.map { Triple(it.instanceId, it.type, it.zoneId) }}")
            val annotations = lifecycleMessages.allAnnotations()
            val shuffle =
                annotations.singleOrNull { it.isType(AnnotationType.Shuffle) }
                    ?: error("No Shuffle; types=${annotations.flatMap { it.typeList }}")
            val resolutionStart =
                annotations.singleOrNull {
                    it.isType(AnnotationType.ResolutionStart) && it.detailInt("grpid") == 95537
                } ?: error(
                    "No Omen ResolutionStart; annotations=${annotations.map { ann ->
                        ann.typeList to ann.detailsList.associate { it.key to (it.valueStringList + it.valueInt32List) }
                    }}",
                )
            val resolutionComplete =
                annotations.singleOrNull {
                    it.isType(AnnotationType.ResolutionComplete) && it.detailInt("grpid") == 95537
                } ?: error("No Omen ResolutionComplete; types=${annotations.flatMap { it.typeList }}")
            val tokenCreated =
                annotations.singleOrNull {
                    it.isType(AnnotationType.TokenCreated)
                } ?: error("No TokenCreated; types=${annotations.flatMap { it.typeList }}")
            val resolveObjectIdChanged =
                annotations.singleOrNull {
                    it.isType(AnnotationType.ObjectIdChanged) && it.detailInt("orig_id") == stackCard.instanceId
                } ?: error(
                    "No resolve ObjectIdChanged; stack=${stackCard.instanceId}, changes=${annotations.filter {
                        it.isType(AnnotationType.ObjectIdChanged)
                    }.map { it.detailsList.associate { detail -> detail.key to detail.valueInt32List } }}",
                )
            val resolveTransfer =
                annotations.singleOrNull {
                    it.isType(AnnotationType.ZoneTransfer_af5a) && it.detailString("category") == "Resolve"
                } ?: error("No Resolve ZoneTransfer; types=${annotations.flatMap { it.typeList }}")
            val libraryZone =
                lifecycleMessages
                    .gameStateMessages()
                    .flatMap { it.zonesList }
                    .firstOrNull { it.zoneId == ZoneIds.P1_LIBRARY && libraryParent.instanceId in it.objectInstanceIdsList }
                    ?: error("No library zone carrying ${libraryParent.instanceId}")
            val deletedIds = lifecycleMessages.gameStateMessages().flatMap { it.diffDeletedInstanceIdsList }

            assertSoftly {
                libraryParent.instanceId shouldBe resolveObjectIdChanged.detailInt("new_id")
                libraryCompanion.parentId shouldBe libraryParent.instanceId
                libraryCompanion.grpId shouldBe 95537
                libraryCompanion.instanceId shouldNotBe stackCompanion.instanceId
                libraryZone.objectInstanceIdsList shouldNotContain libraryCompanion.instanceId
                deletedIds shouldContain stackCompanion.instanceId
                deletedIds shouldContain libraryParent.instanceId
                deletedIds shouldContain libraryCompanion.instanceId
                deletedIds.count { it == libraryParent.instanceId } shouldBe 1
                deletedIds.count { it == libraryCompanion.instanceId } shouldBe 1
                annotations.indexOf(resolutionStart) shouldBeLessThan annotations.indexOf(resolutionComplete)
                annotations.indexOf(resolutionStart) shouldBeLessThan annotations.indexOf(tokenCreated)
                annotations.indexOf(tokenCreated) shouldBeLessThan annotations.indexOf(resolutionComplete)
                tokenCreated.affectorId shouldBe stackCard.instanceId
                shuffle.affectorId shouldBe stackCard.instanceId
                shuffle.detailIntList("OldIds").size shouldBe 5
                shuffle.detailIntList("NewIds") shouldBe libraryZone.objectInstanceIdsList
                shuffle.detailIntList("OldIds").toSet().intersect(shuffle.detailIntList("NewIds").toSet()) shouldBe emptySet()
                annotations.indexOf(shuffle) shouldBeLessThan annotations.indexOf(resolutionComplete)
                annotations.indexOf(resolutionComplete) shouldBeLessThan annotations.indexOf(resolveObjectIdChanged)
                annotations.indexOf(resolveObjectIdChanged) shouldBeLessThan annotations.indexOf(resolveTransfer)
                libraryZone.objectInstanceIdsList.count { it == libraryParent.instanceId } shouldBe 1
            }
        }

        session("Riling Dawnbreaker main face preserves its Omen companion through resolution", puzzle = omenPuzzle) {
            val handParentIid = human.hand.iid("Riling Dawnbreaker")
            val handCardId = checkNotNull(bridge.getForgeCardId(InstanceId(handParentIid)))
            bridge.setSelectedSpellGrpId(handCardId, 95537)
            val castAction =
                allMessages
                    .asReversed()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == handParentIid }

            val lifecycleStart = messageSnapshot()
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.Cast
                        instanceId = castAction.instanceId
                    },
                ),
            )
            drainSink()
            val castMessages = messagesSince(lifecycleStart)
            val stackParent =
                castMessages.allGameObjects().single {
                    it.type == GameObjectType.Card && it.zoneId == ZoneIds.STACK && it.grpId == 95536
                }
            val stackCompanion =
                castMessages.allGameObjects().single {
                    it.type == GameObjectType.Omen_a4aa && it.zoneId == ZoneIds.STACK
                }

            passUntil(maxPasses = 15) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Riling Dawnbreaker" }
            }.shouldBeTrue()

            val battlefieldParent =
                accumulator.objects.values.single {
                    it.type == GameObjectType.Card && it.zoneId == ZoneIds.BATTLEFIELD && it.grpId == 95536
                }
            val battlefieldCompanion =
                accumulator.objects.values.single {
                    it.type == GameObjectType.Omen_a4aa && it.zoneId == ZoneIds.BATTLEFIELD
                }
            assertSoftly {
                stackCompanion.parentId shouldBe stackParent.instanceId
                battlefieldParent.instanceId shouldBe stackParent.instanceId
                battlefieldCompanion.instanceId shouldBe stackCompanion.instanceId
                battlefieldCompanion.parentId shouldBe battlefieldParent.instanceId
                accumulator.zones
                    .getValue(ZoneIds.BATTLEFIELD)
                    .objectInstanceIdsList shouldNotContain battlefieldCompanion.instanceId
            }
        }
    })
