package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Attacker
import wotc.mtgo.gre.external.messaging.Messages.Blocker
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersReq

/**
 * Pins the stateless one-toggle-per-round-trip declaration walk: the committed
 * set is read off the (re-)prompt, diffed against the AI's desired set, and
 * the step is a single toggle until they match — then Submit.
 */
@Suppress("MissingAssertSoftly")
class CombatDeclarationDiffTest :
    FunSpec({

        tags(UnitTag)

        fun attacker(
            iid: Int,
            committed: Boolean,
        ): Attacker =
            Attacker
                .newBuilder()
                .setAttackerInstanceId(iid)
                .apply { if (committed) setSelectedDamageRecipient(DamageRecipient.newBuilder().setPlayerSystemSeatId(2)) }
                .build()

        fun blocker(
            iid: Int,
            blocking: Int? = null,
        ): Blocker =
            Blocker
                .newBuilder()
                .setBlockerInstanceId(iid)
                .apply { if (blocking != null) addSelectedAttackerInstanceIds(blocking) else addAttackerInstanceIds(999) }
                .build()

        test("committed attackers are the entries carrying selectedDamageRecipient") {
            val req =
                DeclareAttackersReq
                    .newBuilder()
                    .addAttackers(attacker(263, committed = true))
                    .addAttackers(attacker(264, committed = false))
                    .build()
            CombatDeclarationDiff.committedAttackers(req) shouldBe setOf(263)
        }

        test("committed blocks are the entries carrying selectedAttackerInstanceIds") {
            val req =
                DeclareBlockersReq
                    .newBuilder()
                    .addBlockers(blocker(10, blocking = 20))
                    .addBlockers(blocker(11))
                    .build()
            CombatDeclarationDiff.committedBlocks(req) shouldBe mapOf(10 to 20)
        }

        test("attacker step toggles one missing desired attacker at a time") {
            val step = CombatDeclarationDiff.attackerStep(committed = setOf(263), desired = setOf(263, 264))
            step.shouldBeInstanceOf<SimDecision.DeclareAttackers>().attackerInstanceIds shouldBe listOf(264)
        }

        test("attacker step un-toggles a committed attacker no longer desired") {
            val step = CombatDeclarationDiff.attackerStep(committed = setOf(263, 264), desired = setOf(263))
            step.shouldBeInstanceOf<SimDecision.DeclareAttackers>().attackerInstanceIds shouldBe listOf(264)
        }

        test("attacker step submits when committed equals desired") {
            CombatDeclarationDiff.attackerStep(committed = setOf(263), desired = setOf(263)) shouldBe SimDecision.SubmitAttackers
        }

        test("attacker step submits immediately when no attack is desired and nothing committed") {
            CombatDeclarationDiff.attackerStep(committed = emptySet(), desired = emptySet()) shouldBe SimDecision.SubmitAttackers
        }

        test("attacker selection ignores unqualified desires and converges through qualified toggles") {
            val req =
                DeclareAttackersReq
                    .newBuilder()
                    .addQualifiedAttackers(attacker(294, committed = false))
                    .addQualifiedAttackers(attacker(311, committed = false))
                    .build()
            val desired = CombatDeclarationDiff.qualifiedDesiredAttackers(req, linkedSetOf(325, 294, 311))

            CombatDeclarationDiff
                .attackerStep(committed = emptySet(), desired = desired)
                .shouldBeInstanceOf<SimDecision.DeclareAttackers>()
                .attackerInstanceIds shouldBe listOf(294)
            CombatDeclarationDiff
                .attackerStep(committed = setOf(294), desired = desired)
                .shouldBeInstanceOf<SimDecision.DeclareAttackers>()
                .attackerInstanceIds shouldBe listOf(311)
            CombatDeclarationDiff.attackerStep(committed = setOf(294, 311), desired = desired) shouldBe
                SimDecision.SubmitAttackers
        }

        test("attacker selection submits no attackers when every desire is unqualified") {
            val req =
                DeclareAttackersReq
                    .newBuilder()
                    .addQualifiedAttackers(attacker(294, committed = false))
                    .build()
            val desired = CombatDeclarationDiff.qualifiedDesiredAttackers(req, linkedSetOf(325))

            CombatDeclarationDiff.attackerStep(committed = emptySet(), desired = desired) shouldBe SimDecision.SubmitAttackers
        }

        test("blocker step assigns one missing blocker at a time") {
            val assign = CombatDeclarationDiff.blockerStep(committed = emptyMap(), desired = mapOf(10 to 20))
            assign.shouldBeInstanceOf<SimDecision.DeclareBlockers>().assignments shouldBe mapOf(10 to 20)
        }

        test("blocker step unassigns before reconsidering a different target") {
            val step = CombatDeclarationDiff.blockerStep(committed = mapOf(10 to 20), desired = mapOf(10 to 21))

            step.shouldBeInstanceOf<SimDecision.UndeclareBlocker>().blockerInstanceId shouldBe 10
        }

        test("blocker step un-toggles a committed blocker no longer desired") {
            val step = CombatDeclarationDiff.blockerStep(committed = mapOf(10 to 20), desired = emptyMap())
            step.shouldBeInstanceOf<SimDecision.UndeclareBlocker>().blockerInstanceId shouldBe 10
        }

        test("blocker step submits when committed equals desired — including the no-blocks case") {
            CombatDeclarationDiff.blockerStep(committed = mapOf(10 to 20), desired = mapOf(10 to 20)) shouldBe
                SimDecision.SubmitBlockers
            CombatDeclarationDiff.blockerStep(committed = emptyMap(), desired = emptyMap()) shouldBe SimDecision.SubmitBlockers
        }

        test("fully committed blocker prompt preserves the server-accepted map") {
            val req =
                DeclareBlockersReq
                    .newBuilder()
                    .addBlockers(blocker(10, blocking = 20))
                    .addBlockers(blocker(11, blocking = 21))
                    .build()

            CombatDeclarationDiff.fullyCommittedBlocks(req) shouldBe mapOf(10 to 20, 11 to 21)
        }

        test("partially committed blocker prompt still requires policy evaluation") {
            val req =
                DeclareBlockersReq
                    .newBuilder()
                    .addBlockers(blocker(10, blocking = 20))
                    .addBlockers(blocker(11))
                    .build()

            CombatDeclarationDiff.fullyCommittedBlocks(req) shouldBe null
        }

        test("blocker selection keeps only blocker-specific offered or selected attackers") {
            val req =
                DeclareBlockersReq
                    .newBuilder()
                    .addBlockers(
                        Blocker
                            .newBuilder()
                            .setBlockerInstanceId(10)
                            .addAttackerInstanceIds(20)
                            .addAttackerInstanceIds(21),
                    ).addBlockers(blocker(11, blocking = 22))
                    .build()

            CombatDeclarationDiff.qualifiedDesiredBlocks(
                req,
                linkedMapOf(10 to 21, 11 to 22, 12 to 20),
            ) shouldBe mapOf(10 to 21, 11 to 22)
            CombatDeclarationDiff.qualifiedDesiredBlocks(req, mapOf(10 to 99, 11 to 20)) shouldBe emptyMap()
        }
    })
