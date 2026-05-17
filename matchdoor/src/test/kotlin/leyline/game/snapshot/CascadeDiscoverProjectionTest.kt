package leyline.game.snapshot

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest

/**
 * End-to-end coverage for the trigger-ability projection on the Stack zone.
 *
 * Locks in the contract that the `Ability` GameObject (zone 27) carries:
 *  - `grpId` = the **ability row id** (Cascade=86, per-card Discover row, etc.)
 *  - `objectSourceGrpId` = the **source card's grpId** (the host permanent)
 *
 * These two fields used to collapse to the same value. The
 * [StackAbilityGrpIdResolver.resolveEntryAbilityGrpId] resolver and the
 * [leyline.game.mapping.ZoneMapper.addStackAbilitiesFromSnapshot] spell-vs-trigger
 * filter together produce the decoupled shape — this Cascade test exercises
 * both via the puzzle harness so a regression in either path fails here, in
 * seconds.
 *
 * Discover coverage is intentionally absent. Discover's ETB-if-cast trigger
 * lifecycle is shorter than the harness's drain-and-pass cycle: Forge fires
 * the trigger, runs the DiscoverEffect, and `MatchFlowHarness.drainSink`
 * auto-accepts the resulting OptionalActionMessage all within one engine
 * tick. The snapshot-after-pass window never sees the trigger on the stack.
 * Tracked separately for a unit-test layer (mockk SA + cardData) that can
 * call [StackAbilityGrpIdResolver.resolveEntryAbilityGrpId] directly.
 */
class CascadeDiscoverProjectionTest :
    SessionTest({

        tags(BoardTag)

        test("Cascade trigger StackEntry resolves grpId=86 and source-card grpId independently") {
            startPuzzleFile("puzzles/cascade-bloodbraid.pzl")

            val cast = harness.castSpellByName("Bloodbraid Elf")
            cast shouldBe true

            val snap = SnapshotCapture.run(harness.game(), harness.bridge, "test", 0)
            val triggerEntries = snap.stack.entries.filter { !it.isSpell }
            triggerEntries shouldHaveSize 1
            val cascadeEntry = triggerEntries.single()

            val bbeGrpId = harness.bridge.cardRepository.findGrpIdByName("Bloodbraid Elf")!!

            assertSoftly {
                cascadeEntry.grpId shouldBe KeywordAbilityIds.CASCADE
                cascadeEntry.grpId shouldBe 86
                cascadeEntry.sourceCardGrpId shouldBe bbeGrpId
                require(cascadeEntry.grpId != cascadeEntry.sourceCardGrpId) {
                    "ability grpId and sourceCardGrpId collapsed back to the same value"
                }
            }
        }
    })
