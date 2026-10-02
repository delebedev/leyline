package leyline.behavior.annotations.addability

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.bundle.BundleBuilder
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
        session(
            "recipient removal preserves the same source's grants on surviving creatures",
            puzzleFile = "data/puzzles/keyword-grant-source-removal.pzl",
        ) {
            val sentinelIid = human.battlefield.iid("Sentinel Sliver")
            val cloudshredderIid = human.battlefield.iid("Cloudshredder Sliver")
            castSpellByName("Lightning Bolt") shouldBe true
            selectTargets(listOf(sentinelIid))
            passUntilResolved()
            val grants =
                bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations.values
                    .filter { AnnotationType.AddAbility_af5a in it.typeList }
            assertSoftly {
                grants.filter { sentinelIid in it.affectedIdsList }.shouldBeEmpty()
                grants.filter { it.affectorId == cloudshredderIid && cloudshredderIid in it.affectedIdsList } shouldHaveSize 2
                accumulator.objects
                    .getValue(cloudshredderIid)
                    .uniqueAbilitiesList
                    .map { it.grpId } shouldContainAll listOf(7, 8)
            }
        }

        session(
            "source removal retires keyword rows while independent and printed flying survive",
            puzzleFile = "data/puzzles/keyword-grant-source-removal.pzl",
        ) {
            val cloudshredderIid = human.battlefield.iid("Cloudshredder Sliver")
            val fervorIid = human.battlefield.iid("Fervor")
            val sentinelIid = human.battlefield.iid("Sentinel Sliver")
            val hawk = human.battlefield.card("Healer's Hawk")
            for ((sourceIid, spell) in listOf(cloudshredderIid to "Lightning Bolt", fervorIid to "Naturalize")) {
                castSpellByName(spell) shouldBe true
                selectTargets(listOf(sourceIid))
                passUntilResolved()
                bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations.values
                    .filter { AnnotationType.AddAbility_af5a in it.typeList && it.affectorId == sourceIid }
                    .shouldBeEmpty()
                val fullState =
                    BundleBuilder(bridge, "keyword-grants", 1)
                        .prepareFullState(checkNotNull(bridge.getGame()), 1000)
                        .result.gsm
                fullState.persistentAnnotationsList
                    .filter { AnnotationType.AddAbility_af5a in it.typeList && it.affectorId == sourceIid }
                    .shouldBeEmpty()
                if (sourceIid == cloudshredderIid) {
                    accumulator.objects
                        .getValue(sentinelIid)
                        .uniqueAbilitiesList
                        .map { it.grpId }
                        .filter { it in listOf(7, 8) } shouldBe listOf(7)
                }
            }
            assertSoftly {
                accumulator.objects
                    .getValue(sentinelIid)
                    .uniqueAbilitiesList
                    .filter { it.grpId in listOf(7, 8) }
                    .shouldBeEmpty()
                hawk.hasKeyword("Flying").shouldBeTrue()
            }
        }
    })
