package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class ActionAttemptLedgerTest :
    FunSpec({
        tags(UnitTag)

        test("attempted and quarantined fingerprints are scoped to current turn") {
            var turn = 1
            val ledger = ActionAttemptLedger { turn }

            ledger.markSubmitted("Cast:1", "perform:Cast")
            ledger.skipFingerprints() shouldBe setOf("Cast:1")

            ledger.markNoProgress()
            ledger.skipFingerprints() shouldBe setOf("Cast:1")
            ledger.stats().outcomes shouldBe mapOf("no-progress" to 1)

            turn = 2
            ledger.skipFingerprints() shouldBe emptySet()
        }

        test("no-pending outcomes are counted separately") {
            val ledger = ActionAttemptLedger { 1 }

            ledger.markNoPending()
            ledger.markNoPending()

            ledger.stats().outcomes shouldBe mapOf("no-pending" to 2)
        }
    })
