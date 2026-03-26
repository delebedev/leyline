package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Persistent-stage annotation pipeline tests — computeBatch lifecycle,
 * DisplayCardUnderCard creation/cleanup, and exile source tracking.
 */
class PersistentAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        // -- DisplayCardUnderCard --

        test("cardExiledWithSourceEmitsDisplayCardUnderCard") {
            val events = listOf(
                GameEvent.CardExiled(forgeCardId = 80, seatId = 1, sourceForgeCardId = 90, fromBattlefield = true),
            )
            val result = AnnotationPipeline.mechanicAnnotations(events, idResolver = ::testResolver)

            result.transient.shouldBeEmpty()
            result.persistent.size shouldBe 1
            val ann = result.persistent[0]
            ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
            ann.affectorId shouldBe testResolver(ForgeCardId(90)).value
            ann.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(80)).value)
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
                resolveInstanceId = { fid -> InstanceId(fid.value + 1000) },
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
                resolveInstanceId = { fid -> InstanceId(fid.value + 1000) },
                resolveForgeCardId = { iid -> forgeCardIdMap[iid.value]?.let { ForgeCardId(it) } },
            )
            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }
    })
