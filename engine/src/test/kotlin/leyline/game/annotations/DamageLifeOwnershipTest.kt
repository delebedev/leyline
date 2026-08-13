package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.DamageSourceKind
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DamageLifeOwnershipTest :
    FunSpec({
        tags(UnitTag)

        fun damageFrame(
            events: List<GameEvent>,
            transfers: List<AppliedTransfer> = emptyList(),
        ) = GameBridge(cardRepository = InMemoryCardRepository()).let { bridge ->
            val transferResult =
                TransferResult(
                    transfers = transfers,
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
                    frameIds = FrameIdResolver(bridge.projectionIdentityWorkspace()),
                )
            pipeline.annotations
        }

        fun resolvingSpellTransfer(
            forgeCardId: Int = 10,
            instanceId: Int = 200,
        ) = AppliedTransfer(
            origId = instanceId,
            newId = instanceId,
            category = TransferCategory.Resolve,
            srcZoneId = ZoneIds.STACK,
            destZoneId = ZoneIds.P1_GRAVEYARD,
            forgeCardId = ForgeCardId(forgeCardId),
            grpId = 105816,
            ownerSeatId = 1,
        )

        test("resolving player damage lands inside lifecycle before the stack exit") {
            val annotations =
                damageFrame(
                    events =
                        listOf(
                            GameEvent.DamageDealtToPlayer(
                                ForgeCardId(10),
                                SeatId(2),
                                amount = 3,
                                sourceKind = DamageSourceKind.SpellOrAbility,
                                changesLife = true,
                            ),
                            GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 17),
                        ),
                    transfers = listOf(resolvingSpellTransfer()),
                )

            assertSoftly {
                annotations.map { it.getType(0) } shouldBe
                    listOf(
                        AnnotationType.ResolutionStart,
                        AnnotationType.DamageDealt_af5a,
                        AnnotationType.SyntheticEvent,
                        AnnotationType.ModifiedLife,
                        AnnotationType.ResolutionComplete,
                        AnnotationType.ZoneTransfer_af5a,
                    )
                annotations[1].detailInt("type") shouldBe 2
                annotations[3].detailInt("life") shouldBe -3
            }
        }

        test("resolving creature damage precedes lifecycle completion and atomic death transfer") {
            val targetDeath =
                AppliedTransfer(
                    origId = 300,
                    newId = 301,
                    category = TransferCategory.SbaDamage,
                    srcZoneId = ZoneIds.BATTLEFIELD,
                    destZoneId = ZoneIds.P2_GRAVEYARD,
                    forgeCardId = ForgeCardId(20),
                    grpId = 75504,
                    ownerSeatId = 2,
                )
            val annotations =
                damageFrame(
                    events =
                        listOf(
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(10),
                                targetCardId = ForgeCardId(20),
                                amount = 3,
                                sourceKind = DamageSourceKind.SpellOrAbility,
                            ),
                        ),
                    transfers = listOf(resolvingSpellTransfer(), targetDeath),
                )

            assertSoftly {
                annotations.map { it.getType(0) } shouldBe
                    listOf(
                        AnnotationType.ResolutionStart,
                        AnnotationType.DamageDealt_af5a,
                        AnnotationType.ResolutionComplete,
                        AnnotationType.ZoneTransfer_af5a,
                        AnnotationType.ObjectIdChanged,
                        AnnotationType.ZoneTransfer_af5a,
                    )
                annotations[1].detailInt("type") shouldBe 2
                annotations.takeLast(2).map { it.getType(0) } shouldBe
                    listOf(AnnotationType.ObjectIdChanged, AnnotationType.ZoneTransfer_af5a)
            }
        }

        test("fight damage stays inside the resolving spell lifecycle") {
            val annotations =
                damageFrame(
                    events =
                        listOf(
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(20),
                                targetCardId = ForgeCardId(30),
                                amount = 3,
                                sourceKind = DamageSourceKind.Fight,
                            ),
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(30),
                                targetCardId = ForgeCardId(20),
                                amount = 2,
                                sourceKind = DamageSourceKind.Fight,
                            ),
                        ),
                    transfers = listOf(resolvingSpellTransfer()),
                )

            annotations.map { it.getType(0) } shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.ZoneTransfer_af5a,
                )
            annotations.slice(1..2).map { it.detailInt("type") } shouldBe listOf(3, 3)
        }

        test("activated ability damage stays inside its stack-ability lifecycle") {
            val annotations =
                damageFrame(
                    events =
                        listOf(
                            GameEvent.DamageDealtToPlayer(
                                ForgeCardId(10),
                                SeatId(2),
                                amount = 1,
                                sourceKind = DamageSourceKind.SpellOrAbility,
                                changesLife = true,
                            ),
                            GameEvent.LifeChanged(SeatId(2), oldLife = 20, newLife = 19),
                            GameEvent.SpellResolved(
                                cardId = ForgeCardId(10),
                                hasFizzled = false,
                                isAbility = true,
                                abilityForgeId = 77,
                                abilityGrpId = 12345,
                            ),
                        ),
                )

            annotations.map { it.getType(0) } shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.SyntheticEvent,
                    AnnotationType.ModifiedLife,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.AbilityInstanceDeleted,
                )
        }

        test("multiple resolution owners do not guess damage ownership") {
            val annotations =
                damageFrame(
                    events =
                        listOf(
                            GameEvent.DamageDealtToPlayer(
                                ForgeCardId(10),
                                SeatId(2),
                                amount = 3,
                                sourceKind = DamageSourceKind.SpellOrAbility,
                                changesLife = true,
                            ),
                        ),
                    transfers =
                        listOf(
                            resolvingSpellTransfer(),
                            resolvingSpellTransfer(forgeCardId = 40, instanceId = 400),
                        ),
                )

            val damageIndex = annotations.indexOfFirst { AnnotationType.DamageDealt_af5a in it.typeList }
            val lastCompleteIndex = annotations.indexOfLast { AnnotationType.ResolutionComplete in it.typeList }
            damageIndex shouldBeGreaterThan lastCompleteIndex
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
