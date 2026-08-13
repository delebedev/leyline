package leyline.mechanics.omen

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.event.FrameEventLog
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.LinkedFaceRole
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class OmenCompanionTest :
    BoardTest({
        test("Omen card projects its linked face outside hand membership") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Riling Dawnbreaker", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val parentIid = board.instanceId(card.id)
            val snap = SnapshotCapture.run(board.game, board.bridge, "test", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                    ).gsm
            val companion = gsm.gameObjectsList.single { it.type == GameObjectType.Omen_a4aa }
            val hand = gsm.zonesList.single { it.zoneId == ZoneIds.P1_HAND }
            val descriptor =
                snap.boundCards
                    .getValue(ForgeCardId(card.id))
                    .linkedFaces
                    .single()

            assertSoftly {
                descriptor.grpId shouldBe 95537
                descriptor.role shouldBe LinkedFaceRole.Omen
                companion.grpId shouldBe 95537
                companion.parentId shouldBe parentIid
                companion.zoneId shouldBe ZoneIds.P1_HAND
                companion.visibility shouldBe Visibility.Private
                companion.ownerSeatId shouldBe 1
                companion.controllerSeatId shouldBe 1
                companion.viewersList shouldBe listOf(1)
                companion.uniqueAbilitiesList.map { it.grpId } shouldBe listOf(188714)
                hand.objectInstanceIdsList shouldContain parentIid
                hand.objectInstanceIdsList shouldNotContain companion.instanceId
            }
        }

        test("Adventure linked face does not project an Omen companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                }
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        SnapshotCapture.run(board.game, board.bridge, "test", 1),
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                    ).gsm

            assertSoftly {
                gsm.gameObjectsList.count { it.type == GameObjectType.Omen_a4aa } shouldBe 0
                gsm.gameObjectsList.count { it.type == GameObjectType.Adventure_a4aa } shouldBe 1
            }
        }

        test("ordinary card projects no linked-face companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                }
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        SnapshotCapture.run(board.game, board.bridge, "test", 1),
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                    ).gsm

            gsm.gameObjectsList.count {
                it.type == GameObjectType.Omen_a4aa || it.type == GameObjectType.Adventure_a4aa
            } shouldBe 0
        }

        test("opponent hidden Omen card has no face companion") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Riling Dawnbreaker", ai, ZoneType.Hand)
                }
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        SnapshotCapture.run(board.game, board.bridge, "test", 1),
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                    ).gsm

            gsm.gameObjectsList.count { it.type == GameObjectType.Omen_a4aa } shouldBe 0
        }

        test("parent reallocation deletes and recreates Omen companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Riling Dawnbreaker", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val oldParentIid = board.instanceId(card.id)
            val oldCompanionIid =
                board.bridge
                    .getOrAllocInstanceId(
                        FrameIdResolver.linkedFaceCompanionForgeId(InstanceId(oldParentIid), LinkedFaceRole.Omen),
                    ).value

            val (diff, newParentIid) = board.transferCard("Riling Dawnbreaker") { moved, game -> exile(moved, game) }
            val companion = diff.gameObjectsList.single { it.type == GameObjectType.Omen_a4aa }
            val exile = diff.zonesList.single { it.zoneId == ZoneIds.EXILE }

            assertSoftly {
                newParentIid shouldNotBe oldParentIid
                companion.instanceId shouldNotBe oldCompanionIid
                companion.parentId shouldBe newParentIid
                companion.zoneId shouldBe ZoneIds.EXILE
                diff.diffDeletedInstanceIdsList shouldContain oldCompanionIid
                exile.objectInstanceIdsList shouldContain newParentIid
                exile.objectInstanceIdsList shouldNotContain companion.instanceId
            }
        }

        test("parent disappearance deletes Omen companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Riling Dawnbreaker", human, ZoneType.Battlefield)
                }
            val card =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val parentFid = ForgeCardId(card.id)
            val parentIid = board.instanceId(card.id)
            val companionIid =
                board.bridge
                    .getOrAllocInstanceId(
                        FrameIdResolver.linkedFaceCompanionForgeId(InstanceId(parentIid), LinkedFaceRole.Omen),
                    ).value
            val prev = SnapshotCapture.run(board.game, board.bridge, "test", 1)

            board.human.getZone(ZoneType.Battlefield).remove(card)
            val cur = SnapshotCapture.run(board.game, board.bridge, "test", 2)
            val diff =
                StateMapper
                    .buildDiff(
                        prev,
                        cur,
                        FrameEventLog.EMPTY,
                        2,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                    ).gsm

            assertSoftly {
                prev.objects.keys shouldContain parentFid
                cur.objects.keys shouldNotContain parentFid
                diff.diffDeletedInstanceIdsList shouldContain parentIid
                diff.diffDeletedInstanceIdsList shouldContain companionIid
                diff.gameObjectsList.count { it.type == GameObjectType.Omen_a4aa } shouldBe 0
                diff.gameObjectsList.map { it.instanceId } shouldNotContain companionIid
            }
        }
    })
