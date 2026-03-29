package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.conformance.detailInt
import leyline.conformance.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Keyword grant annotation pipeline tests — effectAnnotations keyword branch,
 * LayeredEffectCreated/Destroyed, AddAbility pAnn emission, unknown keyword skip.
 */
class KeywordGrantAnnotationTest :
    FunSpec({

        tags(UnitTag)

        test("effectAnnotations emits LayeredEffectCreated + AddAbility pAnn for keyword grant") {
            val boostDiff = EffectTracker.DiffResult(emptyList(), emptyList())
            val kwDiff = EffectTracker.KeywordDiffResult(
                created = listOf(
                    EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L), "Trample"),
                    EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(425, 1L, 5L), "Trample"),
                    EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(432, 1L, 5L), "Trample"),
                ),
                destroyed = emptyList(),
            )
            var uniqueId = 330
            val (transient, persistent) = AnnotationPipeline.effectAnnotations(
                diff = boostDiff,
                keywordDiff = kwDiff,
                keywordAffectorResolver = { _, _, _ -> 435 },
                uniqueAbilityIdAllocator = { uniqueId++ },
            )

            // One LayeredEffectCreated for the keyword effect
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1

            // One AddAbility+LayeredEffect pAnn
            val pAnn = persistent.first { it.typeList.contains(AnnotationType.AddAbility_af5a) }
            pAnn.affectedIdsList shouldHaveSize 3
            pAnn.detailsList.filter { it.key == "UniqueAbilityId" } shouldHaveSize 3
            pAnn.detailUint("grpid") shouldBe 14
        }

        test("effectAnnotations emits LayeredEffectDestroyed for expired keyword") {
            val kwDiff = EffectTracker.KeywordDiffResult(
                created = emptyList(),
                destroyed = listOf(
                    EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L), "Trample"),
                ),
            )
            val (transient, _) = AnnotationPipeline.effectAnnotations(
                diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                keywordDiff = kwDiff,
            )
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectDestroyed) } shouldHaveSize 1
        }

        test("effectAnnotations skips unknown keyword grpIds") {
            val kwDiff = EffectTracker.KeywordDiffResult(
                created = listOf(
                    EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L), "Hexproof"),
                ),
                destroyed = emptyList(),
            )
            val (_, persistent) = AnnotationPipeline.effectAnnotations(
                diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                keywordDiff = kwDiff,
                uniqueAbilityIdAllocator = { 1 },
            )
            persistent.shouldBeEmpty()
        }

        test("effectAnnotations groups same keyword from same static ability into one pAnn") {
            val kwDiff = EffectTracker.KeywordDiffResult(
                created = listOf(
                    // Two creatures get Flying from the same static ability (ts=2, staticId=10)
                    EffectTracker.TrackedKeywordEffect(7020, EffectTracker.KeywordFingerprint(100, 2L, 10L), "Flying"),
                    EffectTracker.TrackedKeywordEffect(7020, EffectTracker.KeywordFingerprint(200, 2L, 10L), "Flying"),
                ),
                destroyed = emptyList(),
            )
            var uniqueId = 400
            val (transient, persistent) = AnnotationPipeline.effectAnnotations(
                diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                keywordDiff = kwDiff,
                keywordAffectorResolver = { _, _, _ -> 500 },
                uniqueAbilityIdAllocator = { uniqueId++ },
            )

            // One transient (LayeredEffectCreated) for the group
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1

            // One persistent pAnn covering both creatures
            persistent shouldHaveSize 1
            val pAnn = persistent[0]
            pAnn.affectedIdsList shouldHaveSize 2
            pAnn.detailUint("grpid") shouldBe 8 // Flying
            pAnn.detailInt("effect_id") shouldBe 7020
        }

        test("effectAnnotations handles mixed P/T boosts and keyword grants") {
            val boostDiff = EffectTracker.DiffResult(
                created = listOf(
                    EffectTracker.TrackedEffect(
                        syntheticId = 7005,
                        fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                        powerDelta = 3,
                        toughnessDelta = 3,
                    ),
                ),
                destroyed = emptyList(),
            )
            val kwDiff = EffectTracker.KeywordDiffResult(
                created = listOf(
                    EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(100, 1L, 5L), "Trample"),
                ),
                destroyed = emptyList(),
            )
            var uniqueId = 330
            val (transient, persistent) = AnnotationPipeline.effectAnnotations(
                diff = boostDiff,
                keywordDiff = kwDiff,
                keywordAffectorResolver = { _, _, _ -> 435 },
                uniqueAbilityIdAllocator = { uniqueId++ },
            )

            // Transient: LayeredEffectCreated (boost) + PtModCreated + LayeredEffectCreated (keyword)
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 2

            // Persistent: LayeredEffect (boost) + AddAbility+LayeredEffect (keyword) = 2 total
            persistent shouldHaveSize 2
            persistent.filter { it.typeList.contains(AnnotationType.ModifiedPower) } shouldHaveSize 1
            persistent.filter { it.typeList.contains(AnnotationType.AddAbility_af5a) } shouldHaveSize 1
        }
    })
