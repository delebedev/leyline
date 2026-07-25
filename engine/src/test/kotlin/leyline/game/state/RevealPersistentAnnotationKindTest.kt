package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult
import leyline.game.iid
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

class RevealPersistentAnnotationKindTest :
    FunSpec({
        tags(UnitTag)

        val frame = FrameContext.INERT
        val emptyEffectDiff = EffectTracker.DiffResult(emptyList(), emptyList())

        fun revealResult(
            faceUp: List<AnnotationInfo>,
            known: List<AnnotationInfo>,
        ) = MechanicAnnotationResult(
            transient = emptyList(),
            persistent = emptyList(),
            perKindPersistent =
                mapOf(
                    CardRevealedKind to faceUp,
                    InstanceRevealedToOpponentKind to known,
                ),
        )

        fun compute(
            current: Map<Int, AnnotationInfo>,
            nextId: Int,
            faceUp: List<AnnotationInfo>,
            known: List<AnnotationInfo>,
        ) = PersistentAnnotationStore.computeBatch(
            currentActive = current,
            startPersistentId = nextId,
            frame = frame,
            effectPersistent = emptyList(),
            effectDiff = emptyEffectDiff,
            transferPersistent = emptyList(),
            mechanicResult = revealResult(faceUp, known),
            resolveInstanceId = { InstanceId(it.value) },
        )

        test("row keys coexist and CardRevealed replacement is independent") {
            val first =
                AnnotationBuilder
                    .cardRevealed(400.iid, 501.iid, 31)
                    .toBuilder()
                    .setId(7)
                    .build()
            val second =
                AnnotationBuilder
                    .cardRevealed(400.iid, 502.iid, 31)
                    .toBuilder()
                    .setId(8)
                    .build()
            val known =
                AnnotationBuilder
                    .instanceRevealedToOpponent(601.iid)
                    .toBuilder()
                    .setId(9)
                    .build()
            val changedFirst = AnnotationBuilder.cardRevealed(400.iid, 501.iid, 35)

            val result = compute(mapOf(7 to first, 8 to second, 9 to known), 10, listOf(changedFirst, second), listOf(known))

            assertSoftly {
                result.deletedIds shouldContainExactlyInAnyOrder listOf(7)
                result.allAnnotations.map { it.affectedIdsList.single() } shouldContainExactlyInAnyOrder
                    listOf(501, 502, 601)
                result.allAnnotations.single { it.affectedIdsList == listOf(501) }.id shouldBe 10
                result.allAnnotations.single { it.affectedIdsList == listOf(502) }.id shouldBe 8
                result.allAnnotations.single { it.affectedIdsList == listOf(601) }.id shouldBe 9
            }
        }

        test("CardRevealed deletion does not delete opponent knowledge") {
            val faceUp =
                AnnotationBuilder
                    .cardRevealed(400.iid, 501.iid, 31)
                    .toBuilder()
                    .setId(7)
                    .build()
            val known =
                AnnotationBuilder
                    .instanceRevealedToOpponent(601.iid)
                    .toBuilder()
                    .setId(8)
                    .build()

            val result = compute(mapOf(7 to faceUp, 8 to known), 9, emptyList(), listOf(known))

            assertSoftly {
                result.deletedIds shouldBe listOf(7)
                result.allAnnotations shouldBe listOf(known)
            }
        }

        test("CardRevealed replaces a same-view row when its affector changes") {
            val first =
                AnnotationBuilder
                    .cardRevealed(400.iid, 501.iid, 31)
                    .toBuilder()
                    .setId(7)
                    .build()
            val changedAffector = AnnotationBuilder.cardRevealed(401.iid, 501.iid, 31)

            val result = compute(mapOf(7 to first), 8, listOf(changedAffector), emptyList())

            assertSoftly {
                result.deletedIds shouldBe listOf(7)
                result.allAnnotations.single().id shouldBe 8
                result.allAnnotations.single().affectorId shouldBe 401
            }
        }
    })
