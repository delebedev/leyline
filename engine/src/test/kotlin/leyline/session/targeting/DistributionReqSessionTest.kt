package leyline.session.targeting

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.hasDetail
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class DistributionReqSessionTest :
    SessionTest({
        session(
            "Arc Lightning allocation resolves uneven damage and updates TargetSpec in place",
            puzzleFile = "puzzles/distribution-arc-lightning.pzl",
        ) {
            val merfolkIid = ai.battlefield.iid("Coral Merfolk")
            val lionsIid = ai.battlefield.iid("Savannah Lions")

            castSpellByName("Arc Lightning") shouldBe true
            allMessages.count { it.hasSelectTargetsReq() } shouldBe 1
            selectTargets(listOf(merfolkIid, lionsIid))

            val distributionMessage = allMessages.last { it.hasDistributionReq() }
            val targetOrder = distributionMessage.distributionReq.targetIdsList
            val preTargetSpec =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.TargetSpec)
                    .last { !it.hasDetail("distributions") }
            val sourceIid = preTargetSpec.affectorId

            assertSoftly {
                distributionMessage.prompt.promptId shouldBe PromptIds.DISTRIBUTE_DAMAGE
                distributionMessage.distributionReq.minAmount shouldBe 3
                distributionMessage.distributionReq.maxAmount shouldBe 3
                distributionMessage.distributionReq.minPerTarget shouldBe 1
                targetOrder.toSet() shouldBe setOf(merfolkIid, lionsIid)
                distributionMessage.distributionReq.validSelectedTargetIdsList shouldContainExactly targetOrder
                preTargetSpec.affectedIdsList shouldContainExactly targetOrder
                preTargetSpec.detailInt("promptId") shouldBe PromptIds.CHOOSE_ANY_TARGET
            }

            // Uneven allocation: one damage to Coral Merfolk and two to Savannah Lions.
            respondToDistribution(listOf(merfolkIid to 1, lionsIid to 2))

            val expectedDistributions = targetOrder.map(mapOf(merfolkIid to 1, lionsIid to 2)::getValue)
            val targetSpecs = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec)
            val postTargetSpec = targetSpecs.last { it.detailIntList("distributions") == expectedDistributions }
            assertSoftly {
                postTargetSpec.id shouldBe preTargetSpec.id
                postTargetSpec.affectorId shouldBe sourceIid
                postTargetSpec.affectedIdsList shouldContainExactly targetOrder
                postTargetSpec.detailIntList("distributions") shouldBe expectedDistributions
            }

            passUntilResolved()
            ai.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContainExactly
                listOf("Coral Merfolk", "Savannah Lions")
        }
    })
