package leyline.game.annotations

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.DamageSourceKind
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DamageLifeOwnershipTest :
    FunSpec({
        tags(UnitTag)

        fun damageFrame(events: List<GameEvent>) =
            GameBridge(cardRepository = InMemoryCardRepository()).let { bridge ->
                val transferResult =
                    TransferResult(
                        transfers = emptyList(),
                        patchedObjects = emptyList(),
                        patchedZones = emptyList(),
                        retiredIds = emptyList(),
                        zoneRecordings = emptyList(),
                    )
                val pipeline =
                    AnnotationPipeline.computeAnnotations(
                        events = events,
                        transferResult = transferResult,
                        actingSeat = 1,
                        bridge = bridge,
                        snap = GsmSnapshot.forTest(),
                        frameIds = FrameIdResolver(bridge),
                    )
                AnnotationOrderEnforcer.enforce(pipeline.annotations)
            }

        test("aggregate player damage owns one canonical life narration") {
            val annotations =
                damageFrame(
                    listOf(
                        GameEvent.PhaseChanged(SeatId(1), phase = 3, step = 7),
                        GameEvent.DamageDealtToPlayer(
                            ForgeCardId(10),
                            SeatId(2),
                            amount = 2,
                            sourceKind = DamageSourceKind.Combat,
                            changesLife = true,
                        ),
                        GameEvent.DamageDealtToPlayer(
                            ForgeCardId(20),
                            SeatId(2),
                            amount = 3,
                            sourceKind = DamageSourceKind.Combat,
                            changesLife = true,
                        ),
                        GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 15),
                    ),
                )

            annotations.map { it.getType(0) } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.SyntheticEvent,
                    AnnotationType.ModifiedLife,
                )
            annotations.last().detailInt("life") shouldBe -5
        }

        test("single player damage still owns its life narration") {
            val annotations =
                damageFrame(
                    listOf(
                        GameEvent.DamageDealtToPlayer(
                            ForgeCardId(10),
                            SeatId(2),
                            amount = 3,
                            sourceKind = DamageSourceKind.Combat,
                            changesLife = true,
                        ),
                        GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 17),
                    ),
                )

            annotations.map { it.getType(0) } shouldBe
                listOf(AnnotationType.DamageDealt_af5a, AnnotationType.SyntheticEvent, AnnotationType.ModifiedLife)
            annotations.last().detailInt("life") shouldBe -3
        }

        test("life loss without damage keeps generic narration") {
            val annotations = damageFrame(listOf(GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 18)))

            annotations.map { it.getType(0) } shouldBe listOf(AnnotationType.ModifiedLife)
            annotations.single().detailInt("life") shouldBe -2
        }

        test("damage and unrelated life loss keep separate owned narration") {
            val annotations =
                damageFrame(
                    listOf(
                        GameEvent.DamageDealtToPlayer(
                            ForgeCardId(10),
                            SeatId(2),
                            amount = 3,
                            sourceKind = DamageSourceKind.Combat,
                            changesLife = true,
                        ),
                        GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 15),
                    ),
                )

            annotations.map { it.getType(0) } shouldBe
                listOf(
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.SyntheticEvent,
                    AnnotationType.ModifiedLife,
                    AnnotationType.ModifiedLife,
                )
            annotations
                .filter { AnnotationType.ModifiedLife in it.typeList }
                .map { it.detailInt("life") } shouldBe listOf(-3, -2)
        }

        test("damage that does not change life cannot own unrelated life loss") {
            val annotations =
                damageFrame(
                    listOf(
                        GameEvent.DamageDealtToPlayer(
                            ForgeCardId(10),
                            SeatId(2),
                            amount = 3,
                            sourceKind = DamageSourceKind.Combat,
                            changesLife = false,
                        ),
                        GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 18),
                    ),
                )

            annotations.count { AnnotationType.DamageDealt_af5a in it.typeList } shouldBe 1
            annotations
                .filter { AnnotationType.ModifiedLife in it.typeList }
                .map { it.detailInt("life") } shouldBe listOf(-2)
        }
    })
