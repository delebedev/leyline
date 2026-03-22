package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.conformance.detailUint
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Unit tests for the three-stage annotation pipeline extracted from
 * [StateMapper.buildFromGame]. Tests [AnnotationPipeline.annotationsForTransfer]
 * as a pure function — no game engine, no bridge, no card DB.
 *
 * Each test constructs an [AnnotationPipeline.AppliedTransfer] and verifies the
 * annotation sequence matches the real client server pattern.
 */
@Suppress("LargeClass")
class AnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        // StateMapper's Kotlin WhenMappings clinit references PhaseType, which
        // requires the card DB to be loaded. Bootstrap once for the whole class.
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
        fun testResolver(forgeCardId: Int): Int = forgeCardId + 1000

        // --- annotationsForTransfer: PlayLand ---

        test("playLandProducesThreeAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

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
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            // ObjectIdChanged should reference origId in affectedIds
            annotations[0].affectedIdsList shouldContain 100
            // ZoneTransfer should reference newId
            annotations[1].affectedIdsList shouldContain 200
        }

        test("playLandProducesPersistentAnnotation") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.PlayLand,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 12345,
                ownerSeatId = 1,
            )
            val (_, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        // --- annotationsForTransfer: CastSpell ---

        test("castSpell with one mana payment produces 8 annotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
                manaPayments = listOf(
                    AnnotationPipeline.ManaPaymentRecord(
                        landInstanceId = 300,
                        manaAbilityInstanceId = 400,
                        color = 2,
                        abilityGrpId = 1002,
                    ),
                ),
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 8
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[2].typeList.first() shouldBe AnnotationType.AbilityInstanceCreated
                annotations[3].typeList.first() shouldBe AnnotationType.TappedUntappedPermanent
                annotations[4].typeList.first() shouldBe AnnotationType.UserActionTaken
                annotations[5].typeList.first() shouldBe AnnotationType.ManaPaid
                annotations[6].typeList.first() shouldBe AnnotationType.AbilityInstanceDeleted
                annotations[7].typeList.first() shouldBe AnnotationType.UserActionTaken
            }

            // AIC details
            assertSoftly {
                annotations[2].affectorId shouldBe 300
                annotations[2].affectedIdsList shouldContain 400
                annotations[2].detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD
            }

            // TUP
            annotations[3].affectorId shouldBe 400
            annotations[3].affectedIdsList shouldContain 300

            // UAT mana
            assertSoftly {
                annotations[4].detailInt("actionType") shouldBe 4
                annotations[4].detailInt("abilityGrpId") shouldBe 1002
                annotations[4].affectedIdsList shouldContain 400
            }

            // ManaPaid
            assertSoftly {
                annotations[5].affectorId shouldBe 300
                annotations[5].affectedIdsList shouldContain 200
                annotations[5].detailInt("color") shouldBe 2
            }

            // AID
            annotations[6].affectorId shouldBe 300
            annotations[6].affectedIdsList shouldContain 400

            // UAT cast
            annotations[7].detailInt("actionType") shouldBe 1
            annotations[7].affectedIdsList shouldContain 200

            // Stack gets EnteredZoneThisTurn (recording confirms)
            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
        }

        test("castSpell with zero mana payments produces 3 annotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
                manaPayments = emptyList(),
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[2].typeList.first() shouldBe AnnotationType.UserActionTaken
                annotations[2].detailInt("actionType") shouldBe 1
            }

            persistent.size shouldBe 1
        }

        test("castSpellUserActionIsCast") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.last().detailInt("actionType") shouldBe 1
        }

        // --- annotationsForTransfer: Resolve ---

        test("resolveProducesThreeAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

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
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 2)

            // Resolve ZoneTransfer should carry actingSeat as affectorId
            annotations[2].affectorId shouldBe 2
        }

        test("resolveUsesGrpId") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations[0].detailUint("grpid") shouldBe 67890
        }

        // --- Edge cases ---

        test("genericZoneTransferProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.ZoneTransfer,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            // ZoneTransfer category produces ObjectIdChanged (when origId != newId) + ZoneTransfer
            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            persistent.shouldBeEmpty()
        }

        test("castSpellToStackGetsPersistentAnnotation") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.STACK,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (_, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            persistent.size shouldBe 1
            persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
            persistent[0].affectorId shouldBe ZoneIds.STACK
        }

        test("resolveToGraveyardNoPersistentAnnotation") {
            // Spell resolves but goes to graveyard (instant/sorcery)
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 200,
                newId = 200,
                category = TransferCategory.Resolve,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 67890,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 3
            persistent.shouldBeEmpty()
        }

        // --- Stage 4: mechanicAnnotations (Group B) ---

        // -- CountersChanged --

        test("counterAddedAnnotation") {
            // Forge sends display name "+1/+1" (from CounterEnumType.getName()), not "P1P1"
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "+1/+1", oldCount = 0, newCount = 2),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)

            assertSoftly {
                result.transient.size shouldBe 1
                result.transient[0].typeList shouldContain AnnotationType.CounterAdded
                result.transient[0].affectedIdsList shouldContain 1042
                result.transient[0].detailString("counter_type") shouldBe "+1/+1"
                result.transient[0].detailInt("transaction_amount") shouldBe 2
            }

            // Persistent: Counter state annotation with current count and correct enum value
            assertSoftly {
                result.persistent.size shouldBe 1
                result.persistent[0].typeList shouldContain AnnotationType.Counter_803b
                result.persistent[0].detailInt("count") shouldBe 2
                result.persistent[0].detailInt("counter_type") shouldBe 1 // P1P1 = 1
            }
        }

        test("counterRemovedAnnotation") {
            // Forge sends "LOYAL" for loyalty counters (CounterEnumType.LOYALTY.getName())
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "LOYAL", oldCount = 5, newCount = 2),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.CounterRemoved
            annotations[0].detailInt("transaction_amount") shouldBe 3
        }

        test("counterUnchangedSkipped") {
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 3, newCount = 3),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient
            annotations.shouldBeEmpty()
        }

        // -- LibraryShuffled --

        xtest("shuffleAnnotation") {
            // Shuffle annotations are suppressed in production (crash client). See commit 76d61d2973.
            val events = listOf(
                GameEvent.LibraryShuffled(seatId = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Shuffle
            annotations[0].affectedIdsList shouldContain 1
        }

        // -- Scry --

        test("scryAnnotation") {
            val events = listOf(
                GameEvent.Scry(seatId = 2, topCount = 1, bottomCount = 2),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Scry_af5a
            annotations[0].detailInt("topCount") shouldBe 1
            annotations[0].detailInt("bottomCount") shouldBe 2
        }

        // -- Surveil --

        test("surveilAnnotation") {
            val events = listOf(
                GameEvent.Surveil(seatId = 1, toLibrary = 1, toGraveyard = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.Scry_af5a
        }

        // -- TokenCreated --

        test("tokenCreatedAnnotation") {
            val events = listOf(
                GameEvent.TokenCreated(forgeCardId = 99, seatId = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TokenCreated
            annotations[0].affectedIdsList shouldContain 1099
        }

        // -- TokenDestroyed --

        test("tokenDestroyedProducesAnnotation") {
            val events = listOf(
                GameEvent.TokenDestroyed(forgeCardId = 88, seatId = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TokenDeleted
            annotations[0].affectorId shouldBe 1088
            annotations[0].affectedIdsList shouldContain 1088
        }

        // -- PowerToughnessChanged --

        test("powerToughnessChangedBothAnnotations") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 4, oldToughness = 3, newToughness = 5),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList shouldContain AnnotationType.ModifiedPower
                annotations[0].affectedIdsList shouldContain 1050
                annotations[0].detailsCount shouldBe 0
                annotations[1].typeList shouldContain AnnotationType.ModifiedToughness
                annotations[1].detailsCount shouldBe 0
                annotations[2].typeList shouldContain AnnotationType.PowerToughnessModCreated
            }
        }

        test("powerOnlyChangedOneAnnotation") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 5, oldToughness = 3, newToughness = 3),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.ModifiedPower
            annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        test("toughnessOnlyChangedOneAnnotation") {
            val events = listOf(
                GameEvent.PowerToughnessChanged(forgeCardId = 50, oldPower = 2, newPower = 2, oldToughness = 3, newToughness = 1),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.ModifiedToughness
            annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
        }

        // -- CardAttached --

        test("attachProducesCorrectAnnotationShape") {
            val events = listOf(
                GameEvent.CardAttached(forgeCardId = 55, targetForgeId = 66, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)

            // Transient: AttachmentCreated
            val created = result.transient[0]
            assertSoftly {
                result.transient.size shouldBe 1
                created.typeList shouldContain AnnotationType.AttachmentCreated
                created.affectorId shouldBe testResolver(55)
                created.affectedIdsList shouldBe listOf(testResolver(66))
            }

            // Persistent: Attachment
            val attach = result.persistent[0]
            assertSoftly {
                result.persistent.size shouldBe 1
                attach.typeList shouldContain AnnotationType.Attachment
                attach.affectorId shouldBe testResolver(55)
                attach.affectedIdsList shouldBe listOf(testResolver(66))
            }
        }

        test("detachReturnsDetachedForgeCardId") {
            val events = listOf(
                GameEvent.CardDetached(forgeCardId = 60, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            result.detachedForgeCardIds shouldBe listOf(60)
        }

        // -- RemoveAttachment --

        test("detachProducesRemoveAttachment") {
            val events = listOf(
                GameEvent.CardDetached(forgeCardId = 60, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            val annotations = result.transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.RemoveAttachment
            annotations[0].affectedIdsList shouldContain testResolver(60)
        }

        // -- Mixed events --

        test("zoneTransferEventsProduceNoTransientButTrackCleanup") {
            val events = listOf(
                GameEvent.ZoneChanged(forgeCardId = 1, from = Zone.Hand, to = Zone.Battlefield),
                GameEvent.LandPlayed(forgeCardId = 1, seatId = 1),
                GameEvent.CardDestroyed(forgeCardId = 2, seatId = 1),
                GameEvent.DamageDealtToPlayer(sourceForgeId = 4, targetSeatId = 1, amount = 3, combat = true),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            result.transient.shouldBeEmpty()
            // CardDestroyed tracks for DisplayCardUnderCard cleanup
            result.exileSourceLeftPlayForgeCardIds shouldBe listOf(2)
        }

        // -- CardTapped --

        test("cardTappedProducesAnnotation") {
            val events = listOf(
                GameEvent.CardTapped(forgeCardId = 70, tapped = true),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
            annotations[0].affectedIdsList shouldContain testResolver(70)
            annotations[0].detailUint("tapped") shouldBe 1
        }

        test("cardUntappedProducesAnnotation") {
            val events = listOf(
                GameEvent.CardTapped(forgeCardId = 71, tapped = false),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient

            annotations.size shouldBe 1
            annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
            annotations[0].detailUint("tapped") shouldBe 0
        }

        test("mechanicAnnotationsMultipleEvents") {
            // NOTE: LibraryShuffled is suppressed in production (crash client). See commit 76d61d2973.
            // Only testing CounterChanged + Scry here (2 events → 2 annotations).
            val events = listOf(
                GameEvent.CountersChanged(forgeCardId = 42, counterType = "P1P1", oldCount = 0, newCount = 1),
                GameEvent.Scry(seatId = 1, topCount = 2, bottomCount = 0),
            )
            val annotations = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver).transient
            annotations.size shouldBe 2
            annotations[0].typeList shouldContain AnnotationType.CounterAdded
            annotations[1].typeList shouldContain AnnotationType.Scry_af5a
        }

        // --- effectAnnotations ---

        test("effectAnnotations emits Created + persistent LayeredEffect for new boost") {
            val created = listOf(
                EffectTracker.TrackedEffect(
                    syntheticId = 7005,
                    fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                    powerDelta = 3,
                    toughnessDelta = 3,
                ),
            )
            val diff = EffectTracker.DiffResult(created, emptyList())

            val (transient, persistent) = AnnotationPipeline.effectAnnotations(diff)

            // Transient: LayeredEffectCreated + PowerToughnessModCreated companion
            assertSoftly {
                transient.size shouldBe 2
                transient[0].typeList.first() shouldBe AnnotationType.LayeredEffectCreated
                transient[0].affectedIdsList shouldBe listOf(7005)
                transient[1].typeList.first() shouldBe AnnotationType.PowerToughnessModCreated
                transient[1].affectedIdsList shouldBe listOf(100)
                transient[1].affectorId shouldBe 100
                transient[1].detailInt("power") shouldBe 3
                transient[1].detailInt("toughness") shouldBe 3
            }

            assertSoftly {
                persistent.size shouldBe 1
                persistent[0].typeList shouldContain AnnotationType.LayeredEffect
                persistent[0].affectedIdsList shouldBe listOf(100)
                persistent[0].detailInt("effect_id") shouldBe 7005
            }
        }

        test("effectAnnotations emits Destroyed for removed boost") {
            val destroyed = listOf(
                EffectTracker.TrackedEffect(
                    syntheticId = 7005,
                    fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                    powerDelta = 3,
                    toughnessDelta = 3,
                ),
            )
            val diff = EffectTracker.DiffResult(emptyList(), destroyed)

            val (transient, persistent) = AnnotationPipeline.effectAnnotations(diff)

            transient.size shouldBe 1
            transient[0].typeList.first() shouldBe AnnotationType.LayeredEffectDestroyed
            transient[0].affectedIdsList shouldBe listOf(7005)
            persistent.shouldBeEmpty()
        }

        test("effectAnnotations empty diff produces no annotations") {
            val diff = EffectTracker.DiffResult(emptyList(), emptyList())
            val (transient, persistent) = AnnotationPipeline.effectAnnotations(diff)
            transient.shouldBeEmpty()
            persistent.shouldBeEmpty()
        }

        test("effectAnnotations uses multi-type array based on deltas (no LayeredEffectType)") {
            // Both power and toughness changed → [ModifiedToughness, ModifiedPower, LayeredEffect]
            val both = EffectTracker.DiffResult(
                listOf(EffectTracker.TrackedEffect(7005, EffectTracker.EffectFingerprint(100, 1L, 0L), 3, 3)),
                emptyList(),
            )
            val (transientBoth, persistentBoth) = AnnotationPipeline.effectAnnotations(both)
            persistentBoth[0].typeList shouldContain AnnotationType.ModifiedPower
            persistentBoth[0].typeList shouldContain AnnotationType.ModifiedToughness
            persistentBoth[0].typeList shouldContain AnnotationType.LayeredEffect
            persistentBoth[0].detailsList.none { it.key == "LayeredEffectType" } shouldBe true
            // Companion PowerToughnessModCreated emitted
            transientBoth.any { it.typeList.contains(AnnotationType.PowerToughnessModCreated) } shouldBe true

            // Only power changed → [ModifiedPower, LayeredEffect], no ModifiedToughness
            val powerOnly = EffectTracker.DiffResult(
                listOf(EffectTracker.TrackedEffect(7006, EffectTracker.EffectFingerprint(101, 2L, 0L), 2, 0)),
                emptyList(),
            )
            val (_, persistentPower) = AnnotationPipeline.effectAnnotations(powerOnly)
            persistentPower[0].typeList shouldContain AnnotationType.ModifiedPower
            persistentPower[0].typeList shouldContain AnnotationType.LayeredEffect
            persistentPower[0].typeList.none { it == AnnotationType.ModifiedToughness } shouldBe true

            // Only toughness changed → [ModifiedToughness, LayeredEffect], no ModifiedPower
            val toughOnly = EffectTracker.DiffResult(
                listOf(EffectTracker.TrackedEffect(7007, EffectTracker.EffectFingerprint(102, 3L, 0L), 0, 1)),
                emptyList(),
            )
            val (_, persistentTough) = AnnotationPipeline.effectAnnotations(toughOnly)
            persistentTough[0].typeList shouldContain AnnotationType.ModifiedToughness
            persistentTough[0].typeList shouldContain AnnotationType.LayeredEffect
            persistentTough[0].typeList.none { it == AnnotationType.ModifiedPower } shouldBe true
        }

        // --- annotationsForTransfer: new zone-specific categories ---

        test("destroyProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Destroy,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            annotations[1].detailString("category") shouldBe "Destroy"
            persistent.shouldBeEmpty()
        }

        test("sacrificeProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Sacrifice"
        }

        test("Sacrifice with mana payment emits full mana-ability bracket") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 95104, // Treasure token
                ownerSeatId = 1,
                manaPayments = listOf(
                    AnnotationPipeline.ManaPaymentRecord(
                        landInstanceId = 100,
                        manaAbilityInstanceId = 200100,
                        color = 8, // Red
                        abilityGrpId = 183,
                        spellInstanceId = 300,
                    ),
                ),
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            assertSoftly {
                annotations.size shouldBe 7
                annotations[0].typeList.first() shouldBe AnnotationType.AbilityInstanceCreated
                annotations[1].typeList.first() shouldBe AnnotationType.TappedUntappedPermanent
                annotations[2].typeList.first() shouldBe AnnotationType.ObjectIdChanged
                annotations[3].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                annotations[4].typeList.first() shouldBe AnnotationType.UserActionTaken
                annotations[5].typeList.first() shouldBe AnnotationType.ManaPaid
                annotations[6].typeList.first() shouldBe AnnotationType.AbilityInstanceDeleted
            }

            // ManaPaid: affectorId = land (origId=100), affectedIds contains spellInstanceId
            annotations[5].affectorId shouldBe 100
            annotations[5].affectedIdsList shouldContain 300
        }

        test("Sacrifice without mana payment emits standard annotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 95104,
                ownerSeatId = 1,
                manaPayments = emptyList(),
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
        }

        test("Sacrifice with mana payment has correct UserActionTaken fields") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Sacrifice,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 95104,
                ownerSeatId = 1,
                manaPayments = listOf(
                    AnnotationPipeline.ManaPaymentRecord(
                        landInstanceId = 100,
                        manaAbilityInstanceId = 200100,
                        color = 8,
                        abilityGrpId = 183,
                        spellInstanceId = 300,
                    ),
                ),
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            val uat = annotations[4]
            assertSoftly {
                uat.typeList.first() shouldBe AnnotationType.UserActionTaken
                uat.detailInt("actionType") shouldBe 4
                uat.detailInt("abilityGrpId") shouldBe 183
            }
        }

        test("bounceProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Bounce,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Bounce"
        }

        test("exileProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Exile,
                srcZoneId = ZoneIds.BATTLEFIELD,
                destZoneId = ZoneIds.EXILE,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Exile"
        }

        test("discardProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Discard,
                srcZoneId = ZoneIds.P1_HAND,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Discard"
        }

        test("drawProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Draw,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Draw"
        }

        test("millProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Mill,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Mill"
        }

        test("surveilProducesAnnotationsWithAffectorId") {
            val abilityInstanceId = 500
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Surveil,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
                affectorId = abilityInstanceId,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.size shouldBe 2

            // ObjectIdChanged carries affectorId
            val oidChanged = annotations[0]
            assertSoftly {
                oidChanged.typeList.first() shouldBe AnnotationType.ObjectIdChanged
                oidChanged.affectorId shouldBe abilityInstanceId
                oidChanged.affectedIdsList shouldContain 100
            }

            // ZoneTransfer carries affectorId and category
            val zt = annotations[1]
            assertSoftly {
                zt.typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
                zt.affectorId shouldBe abilityInstanceId
                zt.affectedIdsList shouldContain 200
                zt.detailString("category") shouldBe "Surveil"
            }
        }

        test("surveilWithoutAffectorIdHasZeroAffector") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Surveil,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
                // no affectorId
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            // Without affectorId, annotations should still work but have 0 affector
            annotations[0].affectorId shouldBe 0
            annotations[1].affectorId shouldBe 0
        }

        test("counteredProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Countered,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
            annotations.last().detailString("category") shouldBe "Countered"
        }

        // --- annotationsForTransfer: Return ---

        test("returnFromGraveyardProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Return,
                srcZoneId = ZoneIds.P1_GRAVEYARD,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
            annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
            annotations[1].detailString("category") shouldBe "Return"
            persistent.size shouldBe 1
        }

        test("returnToHandNoPersistent") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Return,
                srcZoneId = ZoneIds.P1_GRAVEYARD,
                destZoneId = ZoneIds.P1_HAND,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.last().detailString("category") shouldBe "Return"
            persistent.shouldBeEmpty()
        }

        // --- annotationsForTransfer: Search ---

        test("searchProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Search,
                srcZoneId = ZoneIds.P1_LIBRARY,
                destZoneId = ZoneIds.BATTLEFIELD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[1].detailString("category") shouldBe "Search"
            persistent.size shouldBe 1
        }

        // --- annotationsForTransfer: Put ---

        test("putProducesAnnotations") {
            val transfer = AnnotationPipeline.AppliedTransfer(
                origId = 100,
                newId = 200,
                category = TransferCategory.Put,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.P1_GRAVEYARD,
                grpId = 0,
                ownerSeatId = 1,
            )
            val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

            annotations.size shouldBe 2
            annotations[1].detailString("category") shouldBe "Put"
            persistent.shouldBeEmpty()
        }

        // -- DisplayCardUnderCard --

        test("cardExiledWithSourceEmitsDisplayCardUnderCard") {
            val events = listOf(
                GameEvent.CardExiled(forgeCardId = 80, seatId = 1, sourceForgeCardId = 90),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)

            result.transient.shouldBeEmpty()
            result.persistent.size shouldBe 1
            val ann = result.persistent[0]
            ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
            ann.affectorId shouldBe testResolver(90)
            ann.affectedIdsList shouldBe listOf(testResolver(80))
            val tmpZone = ann.detailsList.first { it.key == "TemporaryZoneTransfer" }
            tmpZone.getValueInt32(0) shouldBe 1
        }

        test("cardExiledWithoutSourceDoesNotEmitDisplayCardUnderCard") {
            val events = listOf(
                GameEvent.CardExiled(forgeCardId = 80, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            result.transient.shouldBeEmpty()
            result.persistent.shouldBeEmpty()
        }

        test("cardDestroyedPopulatesExileSourceLeftPlay") {
            val events = listOf(
                GameEvent.CardDestroyed(forgeCardId = 90, seatId = 1),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)
            result.exileSourceLeftPlayForgeCardIds shouldBe listOf(90)
        }

        test("computeBatchRemovesDisplayCardUnderCardWhenSourceLeavesPlay") {
            val ann = AnnotationBuilder.displayCardUnderCard(affectorId = 1090, instanceId = 1080)
                .toBuilder().setId(5).build()
            val active = mapOf(5 to ann)
            val mechanicResult = AnnotationPipeline.MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                exileSourceLeftPlayForgeCardIds = listOf(90),
            )
            val result = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult,
                resolveInstanceId = ::testResolver,
                // Reverse lookup: iid 1090 → forgeCardId 90 (inverse of testResolver)
                resolveForgeCardId = { iid -> ForgeCardId(iid.value - 1000) },
            )
            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(5)
        }

        test("computeBatchRemovesDisplayCardUnderCardAfterZoneTransferRealloc") {
            // Simulates the real bug: Banishing Light (forgeId=1) had iid 111 when
            // DisplayCardUnderCard was created. After destruction (BF→GY), its iid
            // was reallocated to 125. The reverse lookup resolves the OLD iid (111)
            // back to forgeCardId 1, matching the exileSourceLeftPlayForgeCardIds.
            val ann = AnnotationBuilder.displayCardUnderCard(affectorId = 111, instanceId = 116)
                .toBuilder().setId(3).build()
            val active = mapOf(3 to ann)
            val mechanicResult = AnnotationPipeline.MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                exileSourceLeftPlayForgeCardIds = listOf(1), // forgeCardId of Banishing Light
            )
            // Reverse lookup: old iid 111 → forgeCardId 1, new iid 125 → forgeCardId 1
            val forgeCardIdMap = mapOf(111 to 1, 125 to 1)
            val result = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult,
                resolveInstanceId = { it + 1000 },
                resolveForgeCardId = { iid -> forgeCardIdMap[iid.value]?.let { ForgeCardId(it) } },
            )
            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }
    })
