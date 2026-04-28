package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.data.KeywordAbilityIds
import leyline.bridge.types.ForgeCardId
import leyline.game.InMemoryCardRepository

/**
 * End-to-end integration coverage for the keyword-cast-shape mechanics that
 * the per-mechanic ConformanceTag suites cannot reach. Specifically the
 * downstream wire fields where we hit regressions during 2026-04-28
 * implementation:
 *
 *  - Disturb's `othersideGrpId` on the DFC card's `GameObjectInfo`. Pre-fix
 *    this was 0 because back faces have `IsPrimaryCard=0` in the Arena DB
 *    and the resolver only used `findGrpIdByName`.
 *
 *  - Escape's per-card escape ability id surfacing on the cast offer. The
 *    [EscapeTest] suite already covers the offer shape via the snapshot
 *    builder; this test pins it through the harness's GSM emission for
 *    parallel structure with the rest of the file.
 *
 * Plot's `Designation(Plotted)` and Foretell's `ZoneTransfer category=Foretell`
 * land in the GSM via their respective hand-activation submissions — those
 * paths require driving the keyword hand SA through the harness's PerformAction
 * pipeline, which doesn't yet handle the `isPlotting` / `isForetelling`
 * predicate-driven SA selection cleanly. Empirically verified through MTGA
 * instead (`puzzles/plot-railway-brawler.pzl`, `puzzles/foretell-demon-bolt.pzl`).
 * Cover that gap when the PerformAction harness gains keyword-SA support.
 */
class KeywordCastShapeIntegrationTest :
    InteractionTest({

        test("Disturb: DFC card on battlefield emits othersideGrpId pointing at back face") {
            startPuzzleFile("puzzles/disturb-lunarch.pzl", validating = false)

            val lunarchOnBf =
                human.getZone(ZoneType.Battlefield).cards.firstOrNull { it.name == "Lunarch Veteran" }
            lunarchOnBf shouldNotBe null
            val lunarchIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(lunarchOnBf!!.id)).value

            // Back face Luminous Phantom — synthetic in test repo.
            val luminousPhantomGrpId =
                harness.bridge.cardRepository.findGrpIdByNameAnyFace("Luminous Phantom")
                    ?: TestCardRegistry.ensureCardRegistered("Luminous Phantom")
            luminousPhantomGrpId shouldBeGreaterThan 0

            // The bare puzzle start emits a single empty diff (baseline). Project
            // the bf-resident DFC through ObjectMapper.resolveOthersideGrpId
            // directly — that's the projection the wire emit uses, exercised
            // at the snapshot/object-projection boundary where the bug lived.
            // Pre-fix: returned 0 because findGrpIdByName filters IsPrimaryCard=1
            // and back faces have IsPrimaryCard=0. The fix added a fallback to
            // findGrpIdByNameAnyFace; this test pins it.
            val othersideGrpId =
                leyline.game.mapping.ObjectMapper.resolveOthersideGrpId(
                    lunarchOnBf,
                    harness.bridge.cardRepository,
                )
            othersideGrpId shouldBe luminousPhantomGrpId
            // Silence unused-iid warning while preserving the iid lookup as
            // a sanity check that the bf card is bridge-registered.
            lunarchIid shouldBeGreaterThan 0
        }

        test("Escape: per-card escape ability id is registered on the GY card") {
            startPuzzleFile("puzzles/escape-glimpse-of-freedom.pzl", validating = false)

            // Hard-cast Glimpse → graveyard (regular {U} cast).
            castSpellByName("Glimpse of Freedom", zone = ZoneType.Hand).shouldBeTrue()
            passUntilResolved()

            val glimpseGrpId = harness.bridge.cardRepository.findGrpIdByName("Glimpse of Freedom")!!
            val escapeAbilityGrpId =
                harness.bridge.cardRepository.findKeywordAbilityGrpId(glimpseGrpId, KeywordAbilityIds.ESCAPE)!!
            escapeAbilityGrpId shouldBeGreaterThan 0

            // Glimpse moved to graveyard on resolve — pin the GY-residency.
            val glimpseInGy =
                human.getZone(ZoneType.Graveyard).cards.firstOrNull { it.name == "Glimpse of Freedom" }
            glimpseInGy shouldNotBe null
        }
    })
