package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.codes.QualificationType
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val PUZZLE =
    """
    [metadata]
    Name:Frantic Scapegoat Suspected
    Goal:Move Suspected from Scapegoat to another creature.
    Turns:2
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Frantic Scapegoat;Grizzly Bears
    humanbattlefield=Mountain;Forest;Forest;Forest;Forest
    humanlibrary=Mountain;Mountain;Mountain;Mountain
    ailibrary=Island;Island;Island;Island
    """.trimIndent()

private const val SCAPEGOAT_TRANSFER_ABILITY_GRP_ID = 170504

private fun AnnotationInfo.detailInts(key: String): List<Int> = detailsList.filter { it.key == key }.flatMap { it.valueInt32List }

class FranticScapegoatSuspectedTest :
    SessionTest({
        test("Frantic Scapegoat moves Suspected to the chosen entering creature") {
            startPuzzleRaw(PUZZLE, validating = true)

            castSpellByName("Frantic Scapegoat").shouldBeTrue()
            if (game().stackZone.size() > 0) {
                passUntilResolved(maxPasses = 8)
            }

            val scapegoat = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Frantic Scapegoat" }
            val scapegoatIid = human.battlefield.iid(scapegoat)
            val initialDesignation =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.Designation)
                    .firstOrNull {
                        it.affectorId == scapegoatIid &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SUSPECTED
                    }

            assertSoftly {
                scapegoat.isSuspected.shouldBeTrue()
                initialDesignation.shouldNotBeNull()
            }

            val beforeBearCast = messageSnapshot()
            castSpellByName("Grizzly Bears").shouldBeTrue()
            passUntil(maxPasses = 8) { messagesSince(beforeBearCast).any { it.hasSelectNReq() } }.shouldBeTrue()

            val promptMsg = messagesSince(beforeBearCast).last { it.hasSelectNReq() }
            val selectN = promptMsg.selectNReq
            val bearIid = selectN.idsList.single()

            assertSoftly {
                promptMsg.prompt.promptId shouldBe PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES
                promptMsg.allowCancel shouldBe AllowCancel.Continue
                selectN.sourceId shouldBe scapegoatIid
                selectN.idsList shouldContainExactly listOf(bearIid)
                promptMsg.prompt.parametersList[0].numberValue shouldBe scapegoatIid
                promptMsg.prompt.parametersList[1].numberValue shouldBe 1
            }

            respondToSelectN(listOf(bearIid))
            if (game().stackZone.size() > 0) {
                passUntilResolved(maxPasses = 8)
            }

            val bear = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            human.battlefield.iid(bear) shouldBe bearIid
            val gainBear =
                allMessages
                    .annotationsOfType(AnnotationType.GainDesignation)
                    .firstOrNull {
                        it.affectedIdsList.contains(bearIid) &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SUSPECTED
                    }
            val loseScapegoat =
                allMessages
                    .annotationsOfType(AnnotationType.LoseDesignation)
                    .firstOrNull {
                        it.affectedIdsList.contains(scapegoatIid) &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SUSPECTED
                    }
            val bearDesignation =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.Designation)
                    .firstOrNull {
                        it.affectorId == bearIid &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SUSPECTED
                    }
            val bearAbilityRows =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.AddAbility_af5a)
                    .filter { it.affectedIdsList.contains(bearIid) && AnnotationType.LayeredEffect in it.typeList }
            val bearQualificationRows =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.Qualification)
                    .filter { it.affectedIdsList.contains(bearIid) }
            val bearQualificationTypes = bearQualificationRows.map { it.detailInt(DetailKeys.QUALIFICATION_TYPE) }.toSet()
            val bearQualificationGrpIds = bearQualificationRows.map { it.detailInt(DetailKeys.GRPID) }.toSet()
            val transferResolution =
                allMessages
                    .annotationsOfType(AnnotationType.ResolutionComplete)
                    .lastOrNull { it.detailInt(DetailKeys.GRPID) == SCAPEGOAT_TRANSFER_ABILITY_GRP_ID }

            assertSoftly {
                scapegoat.isSuspected.shouldBeFalse()
                bear.isSuspected.shouldBeTrue()
                gainBear.shouldNotBeNull()
                loseScapegoat.shouldNotBeNull()
                bearDesignation.shouldNotBeNull()
                bearAbilityRows
                    .any {
                        it.detailInts(DetailKeys.GRPID).containsAll(
                            listOf(142, AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID.value),
                        )
                    }.shouldBeTrue()
                bearQualificationGrpIds shouldContain 142
                bearQualificationGrpIds shouldContain AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID.value
                bearQualificationTypes shouldContain QualificationType.CombatKeyword.wireValue
                bearQualificationTypes shouldContain QualificationType.CantBlock.wireValue
                val resolution = transferResolution.shouldNotBeNull()
                val bearGain = gainBear.shouldNotBeNull()
                val scapegoatLoss = loseScapegoat.shouldNotBeNull()
                bearGain.affectorId shouldBe resolution.affectorId
                bearGain.affectedIdsList shouldContain bearIid
                scapegoatLoss.affectorId shouldBe resolution.affectorId
                scapegoatLoss.affectedIdsList shouldContain scapegoatIid
            }
        }
    })
