package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.conformance.detailInt
import leyline.conformance.detailUint
import leyline.game.annotations.AppliedTransfer
import leyline.game.annotations.ManaPaymentRecord
import leyline.game.annotations.TransferAnnotations
import leyline.game.annotations.TransferCategory
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Transfer-stage annotation pipeline tests — PlayLand, CastSpell, Resolve,
 * generic ZoneTransfer, and persistent annotation generation for transfers.
 */
class TransferAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        // --- annotationsForTransfer: PlayLand ---

        test("playLandProducesThreeAnnotations") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.PlayLand,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    grpId = 12345,
                    ownerSeatId = 1,
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[2].typeList.first() shouldBe AnnotationType.UserActionTaken
            }

            // UserActionTaken should have actionType=3 (Play)
            annotations[2].detailInt("actionType") shouldBe 3
        }

        test("playLandHasCorrectIds") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.PlayLand,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    grpId = 12345,
                    ownerSeatId = 1,
                )
            val (annotations, _) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            // ObjectIdChanged should reference origId in affectedIds
            annotations[0].affectedIdsList shouldContain 100
            // ZoneTransfer should reference newId
            annotations[1].affectedIdsList shouldContain 200
        }

        test("playLandProducesPersistentAnnotation") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.PlayLand,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    grpId = 12345,
                    ownerSeatId = 1,
                )
            val (_, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        // --- annotationsForTransfer: CastSpell ---

        test("castSpell announce produces 2 annotations (mana block + cast UAT moved to SpellCast event handler)") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.CastSpell,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.STACK,
                    grpId = 67890,
                    ownerSeatId = 1,
                    // manaPayments populated here are intentionally ignored — the
                    // mana bracket is now driven by GameEvent.SpellCast in
                    // MechanicAnnotations, not by AppliedTransfer.
                    manaPayments =
                        listOf(
                            ManaPaymentRecord(
                                landInstanceId = 300,
                                manaAbilityInstanceId = 400,
                                color = 2,
                                abilityGrpId = 1002,
                            ),
                        ),
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            assertSoftly {
                annotations.size shouldBe 2
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            }

            // Stack gets EnteredZoneThisTurn (reference confirms)
            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        test("castSpell announce produces 2 annotations regardless of manaPayments") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.CastSpell,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.STACK,
                    grpId = 67890,
                    ownerSeatId = 1,
                    manaPayments = emptyList(),
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            assertSoftly {
                annotations.size shouldBe 2
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            }

            persistent.size shouldBe 1
        }

        test("castSpell announce no longer emits UAT (deferred to SpellCast event handler)") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.CastSpell,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.STACK,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (annotations, _) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            annotations.none { it.typeList.contains(AnnotationType.UserActionTaken) } shouldBe true
        }

        // --- castSpellEventAnnotations: ability gating ---

        test("castSpellEventAnnotations skips activated abilities") {
            // Activated abilities (e.g. Goblin Fireslinger's tap-to-ping) hit the
            // same Forge GameEventSpellAbilityCast path as real spells, but the
            // source card stays on the battlefield — emitting a `Cast` UAT
            // against its battlefield iid would mis-classify the interaction.
            // The gate is `isAbility`, which Forge's StackItemView sets true
            // for both triggered and activated abilities.
            val ev =
                leyline.game.event.GameEvent.SpellCast(
                    cardId = leyline.bridge.types.ForgeCardId(42),
                    seatId = 1.sid,
                    manaPayments =
                        listOf(
                            leyline.game.event.GameEvent.ManaPayment(
                                sourceCardId = leyline.bridge.types.ForgeCardId(43),
                                color = 4,
                            ),
                        ),
                    isAbility = true,
                    isTrigger = false,
                )
            val annotations =
                TransferAnnotations.castSpellEventAnnotations(
                    ev,
                    idResolver = { leyline.bridge.types.InstanceId(it.value) },
                    manaAbilityGrpIdResolver = { leyline.bridge.types.GrpId(0) },
                )
            annotations.shouldBeEmpty()
        }

        test("castSpellEventAnnotations skips triggered abilities") {
            val ev =
                leyline.game.event.GameEvent.SpellCast(
                    cardId = leyline.bridge.types.ForgeCardId(42),
                    seatId = 1.sid,
                    manaPayments = emptyList(),
                    isAbility = true,
                    isTrigger = true,
                )
            val annotations =
                TransferAnnotations.castSpellEventAnnotations(
                    ev,
                    idResolver = { leyline.bridge.types.InstanceId(it.value) },
                    manaAbilityGrpIdResolver = { leyline.bridge.types.GrpId(0) },
                )
            annotations.shouldBeEmpty()
        }

        test("castSpellEventAnnotations emits cast UAT + mana block on plain spells") {
            val ev =
                leyline.game.event.GameEvent.SpellCast(
                    cardId = leyline.bridge.types.ForgeCardId(100),
                    seatId = 1.sid,
                    manaPayments =
                        listOf(
                            leyline.game.event.GameEvent.ManaPayment(
                                sourceCardId = leyline.bridge.types.ForgeCardId(43),
                                color = 4,
                            ),
                        ),
                    isAbility = false,
                    isTrigger = false,
                )
            val annotations =
                TransferAnnotations.castSpellEventAnnotations(
                    ev,
                    idResolver = { leyline.bridge.types.InstanceId(it.value) },
                    manaAbilityGrpIdResolver = { leyline.bridge.types.GrpId(0) },
                )
            // 5 per-payment annotations (AIC, TUP, UAT-mana, MP, AID) + 1 cast UAT.
            annotations.size shouldBe 6
            annotations.last().typeList shouldContain AnnotationType.UserActionTaken
            annotations.last().detailInt("actionType") shouldBe 1 // Cast
        }

        // --- annotationsForTransfer: Resolve ---

        test("resolveProducesThreeAnnotations") {
            val transfer =
                AppliedTransfer(
                    origId = 200,
                    newId = 200,
                    category = TransferCategory.Resolve,
                    srcZoneId = ZoneIds.STACK,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList.first() shouldBe AnnotationType.ResolutionStart
                annotations[1].typeList.first() shouldBe AnnotationType.ResolutionComplete
                annotations[2].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            }

            // Lands on battlefield — persistent annotation
            persistent.size shouldBe 1
        }

        test("resolveZoneTransferHasActingSeat") {
            val transfer =
                AppliedTransfer(
                    origId = 200,
                    newId = 200,
                    category = TransferCategory.Resolve,
                    srcZoneId = ZoneIds.STACK,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (annotations, _) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 2.sid)

            // Resolve ZoneTransfer should carry actingSeat as affectorId
            annotations[2].affectorId shouldBe 2
        }

        test("resolveUsesGrpId") {
            val transfer =
                AppliedTransfer(
                    origId = 200,
                    newId = 200,
                    category = TransferCategory.Resolve,
                    srcZoneId = ZoneIds.STACK,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (annotations, _) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            annotations[0].detailUint("grpid") shouldBe 67890
        }

        // --- Edge cases ---

        test("genericZoneTransferProducesAnnotations") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.ZoneTransfer,
                    srcZoneId = ZoneIds.EXILE,
                    destZoneId = ZoneIds.P1_GRAVEYARD,
                    grpId = 0,
                    ownerSeatId = 1,
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            // ZoneTransfer category produces ObjectIdChanged (when origId != newId) + ZoneTransfer
            assertSoftly {
                annotations.size shouldBe 2
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                persistent.shouldBeEmpty()
            }
        }

        test("castSpellToStackGetsPersistentAnnotation") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.CastSpell,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.STACK,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (_, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)
            assertSoftly {
                persistent.size shouldBe 1
                persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
                persistent[0].affectorId shouldBe ZoneIds.STACK
            }
        }

        test("resolveToGraveyardNoPersistentAnnotation") {
            // Spell resolves but goes to graveyard (instant/sorcery)
            val transfer =
                AppliedTransfer(
                    origId = 200,
                    newId = 200,
                    category = TransferCategory.Resolve,
                    srcZoneId = ZoneIds.STACK,
                    destZoneId = ZoneIds.P1_GRAVEYARD,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            annotations.size shouldBe 3
            persistent.shouldBeEmpty()
        }
    })
