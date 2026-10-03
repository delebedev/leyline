package leyline.game.annotations

import forge.game.keyword.Keyword
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.MechanicAnnotations
import leyline.game.codes.DetailKeys
import leyline.game.codes.KeywordGrpIds
import leyline.game.state.EffectTracker
import leyline.testkit.detailInt
import leyline.testkit.detailUint
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
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 389, 1L, 5L, "Trample", affector = 435),
                            trackedKeyword(7011, 425, 1L, 5L, "Trample", affector = 435),
                            trackedKeyword(7012, 432, 1L, 5L, "Trample", affector = 435),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 330
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = boostDiff,
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 3
            persistent shouldHaveSize 3
            persistent.forEachIndexed { index, pAnn ->
                assertSoftly {
                    pAnn.affectedIdsList shouldBe listOf(listOf(389, 425, 432)[index])
                    pAnn.detailsList.filter { it.key == "UniqueAbilityId" } shouldHaveSize 1
                    pAnn.detailUint("grpid") shouldBe 14
                    pAnn.detailInt("effect_id") shouldBe 7010 + index
                }
            }
        }

        test("effectAnnotations packs extra ability grpIds for selected keyword grants") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 119, 1L, 5L, "Menace", affector = 114),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 330
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                    keywordExtraAbilityGrpIds = { instanceId, keyword ->
                        if (instanceId.value == 119 && keyword == "Menace") {
                            listOf(AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID)
                        } else {
                            emptyList()
                        }
                    },
                )

            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1
            val pAnn = persistent.single { it.typeList.contains(AnnotationType.AddAbility_af5a) }
            assertSoftly {
                pAnn.affectedIdsList shouldBe listOf(119)
                pAnn.detailsList.filter { it.key == DetailKeys.GRPID }.flatMap { it.valueInt32List } shouldBe
                    listOf(142, 86476)
                pAnn.detailsList.filter { it.key == DetailKeys.UNIQUE_ABILITY_ID } shouldHaveSize 2
                pAnn.detailsList.filter { it.key == DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID }.flatMap { it.valueInt32List } shouldBe
                    listOf(114, 114)
            }
        }

        test("effectAnnotations emits LayeredEffectDestroyed for expired keyword") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created = emptyList(),
                    destroyed =
                        listOf(
                            trackedKeyword(7010, 389, 1L, 5L, "Trample"),
                        ),
                )
            val (transient, _) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                )
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectDestroyed) } shouldHaveSize 1
        }

        test("effectAnnotations skips unknown keyword grpIds") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 389, 1L, 5L, "Flanking"),
                        ),
                    destroyed = emptyList(),
                )
            val (_, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                    uniqueAbilityIdAllocator = { 1 },
                )
            persistent.shouldBeEmpty()
        }

        test("same-source keyword recipients retain independent effect lifetimes") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            // Two creatures get Flying from the same static ability (ts=2, staticId=10)
                            trackedKeyword(7020, 100, 2L, 10L, "Flying", affector = 500),
                            trackedKeyword(7021, 200, 2L, 10L, "Flying", affector = 500),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 400
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            assertSoftly {
                transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 2
                persistent shouldHaveSize 2
                persistent.map { it.affectedIdsList.single() } shouldBe listOf(100, 200)
                persistent.map { it.detailInt("effect_id") } shouldBe listOf(7020, 7021)
                persistent.map { it.detailUint("grpid") } shouldBe listOf(8, 8)
            }
        }

        test("effectAnnotations handles mixed P/T boosts and keyword grants") {
            val boostDiff =
                EffectTracker.DiffResult(
                    created =
                        listOf(
                            EffectTracker.TrackedEffect(
                                syntheticId = 7005,
                                fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                                powerDelta = 3,
                                toughnessDelta = 3,
                            ),
                        ),
                    destroyed = emptyList(),
                )
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 100, 1L, 5L, "Trample", affector = 435),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 330
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = boostDiff,
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            // Transient: LayeredEffectCreated (boost) + PtModCreated + LayeredEffectCreated (keyword)
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 2

            // Persistent: LayeredEffect (boost) + AddAbility+LayeredEffect (keyword) = 2 total
            assertSoftly {
                persistent shouldHaveSize 2
                persistent.filter { it.typeList.contains(AnnotationType.ModifiedPower) } shouldHaveSize 1
                persistent.filter { it.typeList.contains(AnnotationType.AddAbility_af5a) } shouldHaveSize 1
            }
        }

        test("parameterized keyword grants retain exact identities and unknown variants fall back") {
            val keywords =
                listOf(
                    Triple("White", 191, 185),
                    Triple("Blue", 192, 186),
                    Triple("Black", 193, 187),
                    Triple("Red", 194, 188),
                    Triple("Green", 195, 189),
                ).flatMap { (color, hexproofGrpId, protectionGrpId) ->
                    listOf(
                        Keyword.getInstance("Hexproof:$color") to hexproofGrpId,
                        Keyword.getInstance("Protection:$color") to protectionGrpId,
                    )
                }
            keywords.forEach { (keyword, expectedGrpId) ->
                KeywordGrpIds.forKeyword(keyword.title, keyword.keyword.toString()) shouldBe expectedGrpId
            }
            val keywordDiff =
                EffectTracker().diffKeywords(
                    mapOf(
                        100 to
                            keywords.map { (keyword, _) ->
                                EffectTracker.KeywordEntry(
                                    timestamp = 1L,
                                    staticId = 5L,
                                    keyword = keyword.title,
                                    abilityGrpId = KeywordGrpIds.forKeyword(keyword.title, keyword.keyword.toString()),
                                )
                            } +
                            EffectTracker.KeywordEntry(
                                timestamp = 1L,
                                staticId = 5L,
                                keyword = "Hexproof from an unlisted type",
                                abilityGrpId = KeywordGrpIds.forKeyword("Hexproof from an unlisted type", "Hexproof"),
                            ),
                    ),
                )
            var uniqueId = 500
            val (_, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = keywordDiff,
                    keywordAffectorFallbackForgeCardId = ForgeCardId(501),
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            persistent
                .filter { it.typeList.contains(AnnotationType.AddAbility_af5a) }
                .map { it.detailUint(DetailKeys.GRPID) }
                .sorted() shouldBe listOf(2, 185, 186, 187, 188, 189, 191, 192, 193, 194, 195)
        }
    })

private fun trackedKeyword(
    syntheticId: Int,
    cardInstanceId: Int,
    timestamp: Long,
    staticId: Long,
    keyword: String,
    affector: Int? = null,
): EffectTracker.TrackedKeywordEffect =
    EffectTracker.TrackedKeywordEffect(
        syntheticId,
        EffectTracker.KeywordFingerprint(cardInstanceId, timestamp, staticId, keyword),
        keyword,
        affector?.let(::ForgeCardId),
        KeywordGrpIds.forKeyword(keyword),
    )

private fun identityInstanceId(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value)
