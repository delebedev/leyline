package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneIds
import leyline.game.sid
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import leyline.testkit.hasDetail
import wotc.mtgo.gre.external.messaging.Messages.ActionType
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

            assertSoftly {
                annotations[0].affectedIdsList shouldBe listOf(100)
                annotations[1].affectedIdsList shouldBe listOf(200)
            }
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
                // Stack gets EnteredZoneThisTurn (reference confirms)
                persistent.size shouldBe 1
                persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
            }
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

        test("draw to hand produces EnteredZoneThisTurn persistent annotation") {
            val transfer =
                AppliedTransfer(
                    origId = 100,
                    newId = 200,
                    category = TransferCategory.Draw,
                    srcZoneId = ZoneIds.P1_LIBRARY,
                    destZoneId = ZoneIds.P1_HAND,
                    grpId = 67890,
                    ownerSeatId = 1,
                )
            val (_, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            assertSoftly {
                persistent.size shouldBe 1
                persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
                persistent[0].affectorId shouldBe ZoneIds.P1_HAND
                persistent[0].affectedIdsList shouldContain 200
            }
        }

        // --- castSpellEventAnnotations: ability gating ---

        test("castSpellEventAnnotations emits activated ability payment bracket") {
            // Activated abilities hit the same Forge GameEventSpellAbilityCast path
            // as real spells, but their action UAT must be Activate and keyed to
            // the stack ability iid, not a Cast against the source permanent.
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
                    abilityForgeId = 7,
                    abilityGrpId = 139868,
                )
            val annotations =
                TransferAnnotations.castSpellEventAnnotations(
                    ev,
                    idResolver = { leyline.bridge.types.InstanceId(it.value) },
                    manaAbilityGrpIdResolver = { leyline.bridge.types.GrpId(0) },
                    stackInstanceResolver = { leyline.bridge.types.InstanceId(99) },
                )
            val types = annotations.flatMap { it.typeList }

            assertSoftly {
                types shouldContain AnnotationType.AbilityInstanceCreated
                types shouldContain AnnotationType.UserActionTaken
                types shouldContain AnnotationType.ManaPaid
                annotations.last().detailInt(DetailKeys.ACTION_TYPE) shouldBe ActionType.Activate_add3.number
                annotations.last().detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe 139868
                annotations
                    .filter { AnnotationType.ManaPaid in it.typeList }
                    .single()
                    .affectedIdsList
                    .single() shouldBe 99
            }
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
            assertSoftly {
                annotations.size shouldBe 6
                annotations.last().typeList shouldContain AnnotationType.UserActionTaken
                annotations.last().detailInt("actionType") shouldBe 1 // Cast
            }
        }

        test("castSpellEventAnnotations emits Convoke payment bracket") {
            val ev =
                leyline.game.event.GameEvent.SpellCast(
                    cardId = leyline.bridge.types.ForgeCardId(100),
                    seatId = 1.sid,
                    isAbility = false,
                    isTrigger = false,
                )
            val annotations =
                TransferAnnotations.castSpellEventAnnotations(
                    ev,
                    idResolver = { leyline.bridge.types.InstanceId(it.value) },
                    manaAbilityGrpIdResolver = { leyline.bridge.types.GrpId(0) },
                    convokePayments =
                        listOf(
                            TransferAnnotations.ConvokePaymentRecord(
                                paymentForgeCardId = leyline.bridge.types.ForgeCardId(43),
                                color = 7,
                            ),
                        ),
                )

            assertSoftly {
                annotations.map { it.typeList.first() } shouldBe
                    listOf(
                        AnnotationType.AbilityInstanceCreated,
                        AnnotationType.TappedUntappedPermanent,
                        AnnotationType.ResolutionStart,
                        AnnotationType.ManaPaid,
                        AnnotationType.AbilityInstanceDeleted,
                        AnnotationType.UserActionTaken,
                        AnnotationType.UserActionTaken,
                    )
                annotations[2].detailInt(DetailKeys.GRPID) shouldBe KeywordAbilityIds.CONVOKE_PAYMENT
                annotations[3].affectorId shouldBe 43
                annotations[3].affectedIdsList shouldBe listOf(100)
                annotations[3].hasDetail(DetailKeys.ID) shouldBe false
                annotations[3].detailInt(DetailKeys.COLOR) shouldBe 7
                annotations[3].detailInt(DetailKeys.SUBSTITUTION_GRPID) shouldBe KeywordAbilityIds.CONVOKE
                annotations[5].detailInt(DetailKeys.ACTION_TYPE) shouldBe ActionType.MakePayment.number
                annotations[5].detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe KeywordAbilityIds.CONVOKE_PAYMENT
                annotations[6].detailInt(DetailKeys.ACTION_TYPE) shouldBe ActionType.Cast.number
            }
        }

        test("castSpellEventAnnotations emits CastOmen UAT for Omen face casts") {
            val ev =
                leyline.game.event.GameEvent.SpellCast(
                    cardId = leyline.bridge.types.ForgeCardId(100),
                    seatId = 1.sid,
                    isOmen = true,
                )
            val annotations =
                TransferAnnotations.castSpellEventAnnotations(
                    ev,
                    idResolver = { leyline.bridge.types.InstanceId(it.value) },
                    manaAbilityGrpIdResolver = { leyline.bridge.types.GrpId(0) },
                )

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.UserActionTaken
                annotations[0].detailInt("actionType") shouldBe wotc.mtgo.gre.external.messaging.Messages.ActionType.CastOmen.number
                annotations[0].detailInt("abilityGrpId") shouldBe 0
            }
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

        test("resolve with instanceId reallocation resolves old stack id before moving new id") {
            val transfer =
                AppliedTransfer(
                    origId = 200,
                    newId = 201,
                    category = TransferCategory.Resolve,
                    srcZoneId = ZoneIds.STACK,
                    destZoneId = ZoneIds.P1_LIBRARY,
                    grpId = 95537,
                    ownerSeatId = 1,
                )
            val (annotations, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)

            assertSoftly {
                annotations.map { it.typeList.first() } shouldBe
                    listOf(
                        AnnotationType.ResolutionStart,
                        AnnotationType.ResolutionComplete,
                        AnnotationType.ObjectIdChanged,
                        AnnotationType.ZoneTransfer_af5a,
                    )
                annotations[0].affectedIdsList shouldContain 200
                annotations[1].affectedIdsList shouldContain 200
                annotations[2].detailInt("orig_id") shouldBe 200
                annotations[2].detailInt("new_id") shouldBe 201
                annotations[3].affectedIdsList shouldContain 201
                annotations[3].detailInt("zone_src") shouldBe ZoneIds.STACK
                annotations[3].detailInt("zone_dest") shouldBe ZoneIds.P1_LIBRARY
                annotations[3].detailString("category") shouldBe "Resolve"
                persistent.size shouldBe 1
                persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
                persistent[0].affectorId shouldBe ZoneIds.P1_LIBRARY
                persistent[0].affectedIdsList shouldContain 201
            }
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
                persistent.size shouldBe 1
                persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
                persistent[0].affectorId shouldBe ZoneIds.P1_GRAVEYARD
                persistent[0].affectedIdsList shouldContain 200
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

        test("resolveToGraveyardGetsPersistentAnnotation") {
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
            assertSoftly {
                persistent.size shouldBe 1
                persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
                persistent[0].affectorId shouldBe ZoneIds.P1_GRAVEYARD
                persistent[0].affectedIdsList shouldContain 200
            }
        }
    })
