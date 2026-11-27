package leyline.testkit

import leyline.bridge.OfferDiffBucket
import leyline.bridge.OfferDiffDiagnostics
import leyline.bridge.OfferDiffEntry

private val LOST_PLAY_BUCKETS = setOf(OfferDiffBucket.EnumerationGap, OfferDiffBucket.ProjectionDrop)

/** Plays Forge lists that the window offers neither as active nor as inactive actions, and no rule explains. */
fun List<OfferDiffEntry>.lostPlays(): List<OfferDiffEntry> = filter { it.bucket in LOST_PLAY_BUCKETS && it.rule == null }

/** [lostPlays] across every window a session published with the offer diff on. */
fun OfferDiffDiagnostics.Snapshot.lostPlays(): List<OfferDiffDiagnostics.Key> =
    findings.map { it.key }.filter { it.bucket in LOST_PLAY_BUCKETS && it.rule == null }
