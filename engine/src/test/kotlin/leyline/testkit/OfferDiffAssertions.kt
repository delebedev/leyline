package leyline.testkit

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldBeEmpty
import leyline.bridge.OfferDiffBucket
import leyline.bridge.OfferDiffDiagnostics
import leyline.bridge.OfferDiffEntry

private val LOST_PLAY_BUCKETS = setOf(OfferDiffBucket.EnumerationGap, OfferDiffBucket.ProjectionDrop)

/** Plays Forge lists that the window offers neither as active nor as inactive actions, and no rule explains. */
fun List<OfferDiffEntry>.lostPlays(): List<OfferDiffEntry> = filter { it.bucket in LOST_PLAY_BUCKETS && it.rule == null }

/** [lostPlays] across every window a session published with the offer diff on. */
fun OfferDiffDiagnostics.Snapshot.lostPlays(): List<OfferDiffDiagnostics.Key> =
    findings.map { it.key }.filter { it.bucket in LOST_PLAY_BUCKETS && it.rule == null }

/**
 * The session compared at least one window, no comparison threw, and no window
 * lost a play. Without the first two checks an empty or failed probe would pass.
 */
fun OfferDiffDiagnostics.Snapshot.shouldHaveNoLostPlays() {
    assertSoftly {
        windows shouldBeGreaterThan 0
        failures.shouldBeEmpty()
        lostPlays().shouldBeEmpty()
    }
}
