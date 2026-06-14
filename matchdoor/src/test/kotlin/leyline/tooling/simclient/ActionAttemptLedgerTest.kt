package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ActionAttemptLedgerTest :
    FunSpec({
        tags(UnitTag)

        fun castAction(
            instanceId: Int,
            grpId: Int,
            abilityGrpId: Int,
        ): Action =
            Action
                .newBuilder()
                .setActionType(ActionType.Cast)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setAbilityGrpId(abilityGrpId)
                .build()

        fun playLandAction(
            instanceId: Int,
            grpId: Int,
        ): Action =
            Action
                .newBuilder()
                .setActionType(ActionType.Play_add3)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .build()

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

        test("cast retry fingerprint ignores changing instance id within a turn") {
            val first = castAction(instanceId = 10, grpId = 89134, abilityGrpId = 204314)
            val replay = castAction(instanceId = 99, grpId = 89134, abilityGrpId = 204314)
            val differentSpell = castAction(instanceId = 99, grpId = 89135, abilityGrpId = 204314)
            val ledger = ActionAttemptLedger { 1 }

            ledger.markSubmitted(first.retryFingerprints(), "perform:Cast")

            replay.isSkippedBy(ledger.skipFingerprints()) shouldBe true
            differentSpell.isSkippedBy(ledger.skipFingerprints()) shouldBe false
        }

        test("land retry fingerprint remains instance-specific") {
            val first = playLandAction(instanceId = 10, grpId = 1001)
            val second = playLandAction(instanceId = 11, grpId = 1001)
            val ledger = ActionAttemptLedger { 1 }

            ledger.markSubmitted(first.retryFingerprints(), "perform:Play_add3")

            second.isSkippedBy(ledger.skipFingerprints()) shouldBe false
        }
    })
