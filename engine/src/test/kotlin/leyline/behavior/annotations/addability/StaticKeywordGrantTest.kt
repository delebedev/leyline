package leyline.behavior.annotations.addability

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.allPersistentAnnotations
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class StaticKeywordGrantTest :
    SessionTest({

        session(
            "Hallowed Haunting refreshes creatures with flying and vigilance at seven enchantments",
            puzzleFile = "puzzles/enchantment-count-hallowed-haunting.pzl",
            validating = true,
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
