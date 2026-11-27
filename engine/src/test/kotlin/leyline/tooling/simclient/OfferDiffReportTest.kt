package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import leyline.bridge.OfferDiffBucket
import leyline.bridge.OfferDiffDiagnostics
import leyline.bridge.OfferDiffRule

class OfferDiffReportTest :
    FunSpec({
        tags(UnitTag)

        val named =
            OfferDiffDiagnostics.Key(
                OfferDiffBucket.OfferedInactive,
                "Shivan Dragon",
                "Hand",
                "PermanentCreature",
                "Spell 4 R R",
                OfferDiffRule.InactiveUnaffordable,
            )
        val unnamed = named.copy(bucket = OfferDiffBucket.ProjectionDrop, rule = null)

        test("a strict run fails on an unnamed offer difference or a failed comparison, not on named ones") {
            assertSoftly {
                isStrictFailure(
                    stats(OfferDiffDiagnostics.Snapshot(3, listOf(OfferDiffDiagnostics.Finding(named, 3)), emptyMap())),
                ) shouldBe
                    false
                isStrictFailure(
                    stats(OfferDiffDiagnostics.Snapshot(3, listOf(OfferDiffDiagnostics.Finding(unnamed, 1)), emptyMap())),
                ) shouldBe
                    true
                isStrictFailure(
                    stats(OfferDiffDiagnostics.Snapshot(1, emptyList(), mapOf("java.lang.IllegalStateException" to 1))),
                ) shouldBe
                    true
            }
        }

        test("run totals sum windows and each finding across rows") {
            val rows =
                listOf(
                    stats(OfferDiffDiagnostics.Snapshot(4, listOf(OfferDiffDiagnostics.Finding(named, 2)), emptyMap())),
                    stats(
                        OfferDiffDiagnostics.Snapshot(
                            5,
                            listOf(OfferDiffDiagnostics.Finding(named, 1), OfferDiffDiagnostics.Finding(unnamed, 5)),
                            emptyMap(),
                        ),
                    ),
                )
            val totals = OfferDiffReport.totals(rows)
            val json = OfferDiffReport.toJson(totals)
            assertSoftly {
                totals.windows shouldBe 9
                totals.findings.associate { it.key to it.windows } shouldBe mapOf(unnamed to 5, named to 3)
                totals.unnamed.map { it.key } shouldBe listOf(unnamed)
                json shouldContain "\"byBucket\":{\"EnumerationGap\":0,\"ProjectionDrop\":1,\"OfferedInactive\":1,\"OfferedNotInForge\":0}"
                json shouldContain "\"rule\":\"InactiveUnaffordable\""
            }
        }
    })

private fun stats(offerDiff: OfferDiffDiagnostics.Snapshot): GameStats =
    GameStats(
        turn = 1,
        gameOver = true,
        iterations = 1,
        totalMessages = 1,
        promptHistogram = emptyMap(),
        hitIterCap = false,
        completionReason = "natural",
        offerDiff = offerDiff,
    )
