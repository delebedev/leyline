package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class TargetSpecConformanceTest :
    SessionTest({
        session(
            "multiple targets in one group share one TargetSpec with aligned distributions",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Twin Bolt
                humanbattlefield=Mountain;Mountain;Centaur Courser
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            val opponentIid = ai.battlefield.iid("Grizzly Bears")
            castSpellByName("Twin Bolt") shouldBe true

            val request = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val selection = request.targetsList.single()
            assertSoftly {
                request.abilityGrpId shouldBe 95649
                selection.targetIdx shouldBe 1
                selection.targetingAbilityGrpId shouldBe 90088
                selection.prompt.promptId shouldBe PromptIds.CHOOSE_ANY_TARGET
            }

            selectTargets(listOf(OPPONENT_SEAT, opponentIid))

            val targetSpecs = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec)
            targetSpecs.shouldHaveSize(1)
            val targetSpec = targetSpecs.single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT, opponentIid)
                targetSpec.detailInt("abilityGrpId") shouldBe 90088
                targetSpec.detailInt("index") shouldBe 1
                targetSpec.detailInt("promptId") shouldBe PromptIds.CHOOSE_ANY_TARGET
                targetSpec.detailInt("promptParameters") shouldBe targetSpec.affectorId
                targetSpec.detailIntList("distributions") shouldBe listOf(1, 1)
            }
        }

        session(
            "partial divided target set uses Forge's final allocation",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Twin Bolt
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            castSpellByName("Twin Bolt") shouldBe true
            selectTargets(listOf(OPPONENT_SEAT))

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                targetSpec.detailIntList("distributions") shouldBe listOf(2)
            }
        }

        session(
            "generic target prompt id is shared by request and TargetSpec",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Stone Rain
                humanbattlefield=Mountain;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Forest
                ailibrary=Mountain
                """,
        ) {
            val targetIid = ai.battlefield.iid("Forest")
            castSpellByName("Stone Rain") shouldBe true
            val requestPromptId =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
                    .prompt.promptId
            requestPromptId shouldBe PromptIds.SELECT_TARGETS

            selectTargets(listOf(targetIid))
            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            targetSpec.detailInt("promptId") shouldBe requestPromptId
        }

        session(
            "Forge-selected opponent still emits TargetSpec",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Pilfer
                humanbattlefield=Swamp;Swamp
                humanlibrary=Swamp
                aihand=Grizzly Bears
                aibattlefield=Mountain
                ailibrary=Mountain
                """,
        ) {
            castSpellByName("Pilfer") shouldBe true

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                targetSpec.detailInt("promptId") shouldBe PromptIds.SELECT_TARGETS
            }
        }
    })
