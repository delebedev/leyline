package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.beInGraveyardOf
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Bushwhack is `SP$ Charm | Choices$ FetchBasic,Fight`. The Fight branch is
 * `Pump|AILogic$ Fight + DBFight|ParentTarget` — same shape as Bite Down,
 * but wrapped in an outer Charm whose own SA does not target.
 *
 * Pre-fix bug: PlayerController.playChosenSpellAbility computed
 * `needsTargeting = sa.usesTargeting() && sa.targets.isEmpty()` against the
 * outer Charm SA, which never targets. mayChooseTargets=false → setupTargets()
 * skipped → sub-SA chain never gets chooseTargetsFor → no SelectTargetsReq.
 * The cast silently dropped after the modal pick.
 */
class BushwhackFightTest :
    SessionTest({

        session(
            "Charm spell with zero legal modes is not offered as castable",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Destroy Evil;Plains
                humanbattlefield=Plains;Plains
                humanlibrary=Plains
                aibattlefield=Trufflesnout
                ailibrary=Mountain
                """,
        ) {
            val actions = accumulator.actions.shouldNotBeNull()
            val destroyEvilIid = human.hand.iid("Destroy Evil")
            actions.actionsList.filter { it.actionType == ActionType.Cast && it.instanceId == destroyEvilIid } shouldBe emptyList()
        }

        session("Bushwhack Fight mode shares exact target metadata with TargetSpec", puzzleFile = "puzzles/bushwhack-fight.pzl") {
            val ownIid = human.battlefield.iid("Centaur Courser")
            val oppIid = ai.battlefield.iid("Grizzly Bears")

            val cto = castSpellUntilCastingTimeOptionsReq("Bushwhack")
            cto.castingTimeOptionReqCount shouldBe 1
            val modalReq = cto.getCastingTimeOptionReq(0).modalReq
            modalReq.modalOptionsCount shouldBe 2

            // Bushwhack: `SP$ Charm | Choices$ FetchBasic,Fight` — order preserved
            // by CharmEffect.makePossibleOptions, so index 1 = Fight.
            val fightOption = modalReq.getModalOptions(1)

            // Pre-fix: zero SelectTargetsReq emitted, cast silently drops.
            // Post-fix: first SelectTargetsReq for "creature you control".
            val firstSt =
                after { respondModalChoice(listOf(fightOption.grpId)) }
                    .messages
                    .firstOrNull { it.hasSelectTargetsReq() }
            firstSt.shouldNotBeNull()
            val firstSelection = firstSt.selectTargetsReq.targetsList.first()
            assertSoftly {
                firstSt.selectTargetsReq.abilityGrpId shouldBe 93928
                firstSelection.targetIdx shouldBe 1
                firstSelection.targetingAbilityGrpId shouldBe fightOption.grpId
                firstSelection.prompt.promptId shouldBe PromptIds.TARGET_CREATURE_YOU_CONTROL
                firstSelection.prompt.parametersList
                    .single()
                    .numberValue shouldBe firstSt.selectTargetsReq.sourceId
                firstSelection.minTargets shouldBe 1
                firstSelection.maxTargets shouldBe 1
                firstSelection.targetsList
                    .map { it.targetInstanceId }
                    .shouldContain(ownIid)
            }

            val secondPromptSlice = after { selectTargets(listOf(ownIid)) }
            val secondSt =
                secondPromptSlice.messages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
            val firstActiveSpec =
                secondPromptSlice.messages
                    .persistentAnnotationsOfType(AnnotationType.TargetSpec)
                    .single { it.detailInt("index") == 1 }
            val secondSelection = secondSt.targetsList.single()
            assertSoftly {
                firstActiveSpec.affectorId shouldBe firstSt.selectTargetsReq.sourceId
                secondSt.abilityGrpId shouldBe 93928
                secondSelection.targetIdx shouldBe 2
                secondSelection.targetingAbilityGrpId shouldBe fightOption.grpId
                secondSelection.prompt.promptId shouldBe PromptIds.TARGET_CREATURE_YOU_DONT_CONTROL
                secondSelection.prompt.parametersList
                    .single()
                    .numberValue shouldBe secondSt.sourceId
                secondSelection.targetsList.map { it.targetInstanceId }.shouldContain(oppIid)
            }

            val submittedSlice = after { selectTargets(listOf(oppIid)) }
            val secondSpecGsm =
                submittedSlice.messages
                    .gameStateMessages()
                    .single { gsm ->
                        gsm.persistentAnnotationsList.any {
                            AnnotationType.TargetSpec in it.typeList && it.detailInt("index") == 2
                        }
                    }
            val secondSpec =
                secondSpecGsm.persistentAnnotationsList.single {
                    AnnotationType.TargetSpec in it.typeList && it.detailInt("index") == 2
                }
            val firstSpec = firstActiveSpec
            assertSoftly {
                secondSpecGsm.diffDeletedPersistentAnnotationIdsList shouldNotContain firstSpec.id
                firstSpec.affectorId shouldBe secondSpec.affectorId
                firstSpec.affectedIdsList shouldBe listOf(ownIid)
                secondSpec.affectedIdsList shouldBe listOf(oppIid)
                firstSpec.detailInt("abilityGrpId") shouldBe fightOption.grpId
                secondSpec.detailInt("abilityGrpId") shouldBe fightOption.grpId
                firstSpec.detailInt("promptId") shouldBe PromptIds.TARGET_CREATURE_YOU_CONTROL
                secondSpec.detailInt("promptId") shouldBe PromptIds.TARGET_CREATURE_YOU_DONT_CONTROL
                firstSpec.detailInt("promptParameters") shouldBe firstSpec.affectorId
                secondSpec.detailInt("promptParameters") shouldBe secondSpec.affectorId
            }
        }

        session("Bushwhack Fight resolves: mutual damage, both creatures take damage", puzzleFile = "puzzles/bushwhack-fight.pzl") {
            val ownIid = human.battlefield.iid("Centaur Courser")
            val oppIid = ai.battlefield.iid("Grizzly Bears")

            val cto = castSpellUntilCastingTimeOptionsReq("Bushwhack")
            val fightOption = cto.getCastingTimeOptionReq(0).modalReq.getModalOptions(1)
            respondModalChoice(listOf(fightOption.grpId))

            selectTargets(listOf(ownIid))
            selectTargets(listOf(oppIid))
            passUntilResolved()

            // DamageDealt fires twice for fight (each creature deals to the other).
            val damage = allMessages.annotationsOfType(AnnotationType.DamageDealt_af5a)
            assertSoftly {
                damage.shouldHaveSize(2)
                damage.map { it.detailInt("type") } shouldBe listOf(3, 3)
            }

            // Bushwhack moved Stack→GY, not countered.
            assertSoftly {
                "Bushwhack" should beInGraveyardOf(human, count = 1)
                // Grizzly Bears (2/2) takes 3 damage from Centaur Courser → dies.
                "Grizzly Bears" should beInGraveyardOf(ai)
            }
        }
    })
