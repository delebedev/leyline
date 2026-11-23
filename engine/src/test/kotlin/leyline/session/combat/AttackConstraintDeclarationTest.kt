package leyline.session.combat

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.tooling.headless.HeadlessResponseMode
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq

/**
 * Attack restrictions and requirements reach the declaration prompt, and a declaration
 * Forge rejects leaves no attackers behind, so the next declaration is judged alone.
 */
class AttackConstraintDeclarationTest :
    SessionTest({

        fun MatchFlowHarness.selectAttackers(instanceIds: List<Int>): DeclareAttackersReq =
            after { toggleAttackers(instanceIds) }.expectOneDeclareAttackersReq()

        /** Submits the current selection and returns the request Forge re-opens after rejecting it. */
        fun MatchFlowHarness.submitRejectedAttack(): DeclareAttackersReq {
            val reopened = after { submitAttackers() }.expectOneDeclareAttackersReq()
            assertSoftly {
                phase() shouldBe "COMBAT_DECLARE_ATTACKERS"
                game()
                    .combat
                    ?.attackers
                    .orEmpty()
                    .shouldBeEmpty()
                reopened.attackersList.none { it.hasSelectedDamageRecipient() }.shouldBeTrue()
            }
            return reopened
        }

        fun MatchFlowHarness.finishCombat(): Boolean = passUntil(maxPasses = 10) { phase() == "MAIN2" || isGameOver() }

        session(
            "a global attacker maximum blocks submitting two attackers and accepts one after a rejected submit",
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Silent Arbiter;Grizzly Bears;Savannah Lions
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """,
        ) {
            advanceToCombat()
            val bears = human.battlefield.iid("Grizzly Bears")
            val lions = human.battlefield.iid("Savannah Lions")
            val initial = allMessages.last { it.hasDeclareAttackersReq() }.declareAttackersReq
            assertSoftly {
                initial.hasRestrictions.shouldBeTrue()
                initial.canSubmitAttackers.shouldBeTrue()
            }

            val oneSelected = selectAttackers(listOf(bears))
            val twoSelected = selectAttackers(listOf(lions))
            val reopened = submitRejectedAttack()
            declareAttackers(listOf(bears))
            val settled = finishCombat()
            assertSoftly {
                oneSelected.canSubmitAttackers.shouldBeTrue()
                twoSelected.canSubmitAttackers.shouldBeFalse()
                reopened.hasRestrictions.shouldBeTrue()
                settled.shouldBeTrue()
                ai.life shouldBe 18
            }
        }

        session(
            "a creature that cannot attack alone blocks a solo submit and attacks with a partner after a rejected submit",
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Loyal Pegasus;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """,
        ) {
            advanceToCombat()
            val pegasus = human.battlefield.iid("Loyal Pegasus")
            val bears = human.battlefield.iid("Grizzly Bears")

            val solo = selectAttackers(listOf(pegasus))
            val paired = selectAttackers(listOf(bears))
            val soloAgain = after { deselectAttackers(listOf(bears)) }.expectOneDeclareAttackersReq()
            submitRejectedAttack()
            declareAttackers(listOf(pegasus, bears))
            val settled = finishCombat()
            assertSoftly {
                solo.hasRestrictions.shouldBeTrue()
                solo.canSubmitAttackers.shouldBeFalse()
                paired.canSubmitAttackers.shouldBeTrue()
                soloAgain.canSubmitAttackers.shouldBeFalse()
                settled.shouldBeTrue()
                ai.life shouldBe 16
            }
        }

        session(
            "an unmet attack requirement blocks submit and accepts the required attacker after a rejected submit",
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Bloodrock Cyclops;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """,
        ) {
            advanceToCombat()
            val cyclops = human.battlefield.iid("Bloodrock Cyclops")
            val bears = human.battlefield.iid("Grizzly Bears")
            val initial = allMessages.last { it.hasDeclareAttackersReq() }.declareAttackersReq
            val withoutRequired = selectAttackers(listOf(bears))
            val reopened = submitRejectedAttack()
            declareAttackers(listOf(cyclops))
            val settled = finishCombat()
            assertSoftly {
                initial.hasRequirements.shouldBeTrue()
                initial.canSubmitAttackers.shouldBeFalse()
                withoutRequired.canSubmitAttackers.shouldBeFalse()
                reopened.hasRequirements.shouldBeTrue()
                settled.shouldBeTrue()
                ai.life shouldBe 17
            }
        }

        session(
            "a player choosing another player's attackers is held to that player's attacker maximum",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Master Warcraft
                humanbattlefield=Mountain;Mountain;Mountain;Mountain
                humanlibrary=Forest;Forest;Forest
                aibattlefield=Silent Arbiter;Centaur Courser;Grizzly Bears
                ailibrary=Island;Island;Island
                """,
            turns = 4,
            aiScript = listOf(ScriptedAction.PassPriority),
        ) {
            val courser = ai.battlefield.iid("Centaur Courser")
            val bears = ai.battlefield.iid("Grizzly Bears")
            val atHuman =
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.Player_a0e5)
                    .setPlayerSystemSeatId(1)
                    .build()
            castSpellByName("Master Warcraft").shouldBeTrue()
            passUntil(maxPasses = 20) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            val one =
                after {
                    toggleAttackers(
                        listOf(courser),
                        damageRecipients = mapOf(courser to atHuman),
                    )
                }.expectOneDeclareAttackersReq()
            val two = after { toggleAttackers(listOf(bears), damageRecipients = mapOf(bears to atHuman)) }.expectOneDeclareAttackersReq()
            val reopened = submitRejectedAttack()
            toggleAttackers(listOf(courser), damageRecipients = mapOf(courser to atHuman))
            submitAttackers()
            passUntil(maxPasses = 40) { human.life == 17 }
            assertSoftly {
                one.hasRestrictions.shouldBeTrue()
                one.canSubmitAttackers.shouldBeTrue()
                two.canSubmitAttackers.shouldBeFalse()
                reopened.hasRestrictions.shouldBeTrue()
                human.life shouldBe 17
            }
        }
    })
