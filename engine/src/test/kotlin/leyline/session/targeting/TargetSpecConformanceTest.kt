package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class TargetSpecConformanceTest :
    SessionTest({
        test("multiple targets in one group share one TargetSpec with aligned distributions") {
            startPuzzle(
                """
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
                name = "Twin Bolt target distribution",
                validating = true,
            )

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

        test("partial divided target set uses Forge's final allocation") {
            startPuzzle(
                """
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
                name = "Twin Bolt single target distribution",
                validating = true,
            )

            castSpellByName("Twin Bolt") shouldBe true
            selectTargets(listOf(OPPONENT_SEAT))

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                targetSpec.detailIntList("distributions") shouldBe listOf(2)
            }
        }

        test("generic target prompt id is shared by request and TargetSpec") {
            startPuzzle(
                """
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
                name = "Stone Rain target prompt",
                validating = true,
            )

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

        test("Forge-selected opponent still emits TargetSpec") {
            startPuzzle(
                """
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
                name = "Pilfer automatic opponent target",
                validating = true,
            )

            castSpellByName("Pilfer") shouldBe true

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                targetSpec.detailInt("promptId") shouldBe PromptIds.SELECT_TARGETS
            }
        }
    })
