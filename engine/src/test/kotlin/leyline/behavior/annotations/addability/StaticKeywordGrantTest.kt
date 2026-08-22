package leyline.behavior.annotations.addability

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allPersistentAnnotations
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val HALLOWED_HAUNTING_PUZZLE =
    """
    [metadata]
    Name:Enchantment Count Hallowed Haunting
    Goal:Demo
    Turns:3
    Difficulty:Easy
    Description:Cast the seventh enchantment. NumberOfEnchantmentYouControl reaches seven and grants creatures flying and vigilance.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    removesummoningsickness=true

    humanbattlefield=Hallowed Haunting;Authority of the Consuls;Authority of the Consuls;Authority of the Consuls;Authority of the Consuls;Authority of the Consuls;Savannah Lions;Plains;Plains
    humanhand=Pacifism
    humanlibrary=Plains
    ailibrary=Forest
    aibattlefield=Grizzly Bears
    """.trimIndent()

class StaticKeywordGrantTest :
    SessionTest({

        session(
            "Hallowed Haunting refreshes creatures with flying and vigilance at seven enchantments",
            puzzle = HALLOWED_HAUNTING_PUZZLE,
        ) {
            val hallowedIid = human.battlefield.iid("Hallowed Haunting")
            val lionsIid = human.battlefield.iid("Savannah Lions")
            val targetIid = ai.battlefield.iid("Grizzly Bears")
            val slice =
                after {
                    castSpellByName("Pacifism") shouldBe true
                    selectTargets(listOf(targetIid))
                    submitTargets()
                    passUntilResolved()
                }

            val refreshedLions =
                slice.messages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .flatMap { it.gameObjectsList }
                    .last { it.instanceId == lionsIid }
            val keywordGrants =
                slice.messages
                    .allPersistentAnnotations()
                    .filter { AnnotationType.AddAbility_af5a in it.typeList && lionsIid in it.affectedIdsList }

            assertSoftly {
                refreshedLions.uniqueAbilitiesList.map { it.grpId } shouldContainAll listOf(8, 15)
                keywordGrants shouldHaveSize 2
                keywordGrants.map { it.affectorId }.toSet() shouldBe setOf(hallowedIid)
            }
        }
    })
