package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beOnBattlefieldOf

class GenericEffectChoiceTest :
    SessionTest({
        for ((index, token) in listOf("Food", "Treasure").withIndex()) {
            session(
                "Tireless Provisioner landfall offers both tokens and creates selected $token",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Tireless Provisioner
                    humanhand=Forest
                    humanlibrary=Forest;Forest;Forest;Forest;Forest
                    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                    """.trimIndent(),
                turns = 3,
            ) {
                playLand("Forest").shouldBeTrue()
                passUntil(maxPasses = 3) {
                    allMessages.any { it.hasCastingTimeOptionsReq() }
                }.shouldBeTrue()
                val modal =
                    allMessages
                        .last { it.hasCastingTimeOptionsReq() }
                        .castingTimeOptionsReq
                        .getCastingTimeOptionReq(0)
                        .modalReq
                assertSoftly {
                    modal.minSel shouldBe 1
                    modal.maxSel shouldBe 1
                    modal.modalOptionsList.map { bridge.cardRepository.findAbilityLocalization(it.grpId)?.text } shouldBe
                        listOf("Food", "Treasure")
                }
                respondModalChoice(listOf(modal.getModalOptions(index).grpId))
                passUntilResolved()
                "$token Token" should beOnBattlefieldOf(human)
                "Tireless Provisioner" should beOnBattlefieldOf(human)
            }
        }
    })
