package leyline.behavior.annotations.qualification

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.codes.DetailKeys
import leyline.game.codes.QualificationType
import leyline.testkit.CardDataDeriver
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allPersistentAnnotations
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

class CombatQualificationSessionTest :
    SessionTest({
        session(
            "cast Pacifism emits can't attack and can't block Qualifications",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Pacifism
                humanbattlefield=Plains;Plains
                humanlibrary=Plains
                aibattlefield=Grizzly Bears
                ailibrary=Island
                """.trimIndent(),
            validating = true,
        ) {
            val targetIid = ai.battlefield.iid("Grizzly Bears")
            val aura = human.getZone(ZoneType.Hand).cards.first { it.name == "Pacifism" }
            bridge.abilityRegistryFor(
                aura,
                CardDataDeriver.fromForgeCard(aura, "Pacifism").copy(
                    abilityIds = emptyList(),
                    abilityKinds = emptyList(),
                ),
            )

            val slice =
                after {
                    castSpellByName("Pacifism") shouldBe true
                    selectTargets(listOf(targetIid))
                    submitTargets()
                    passUntilResolved()
                }

            val qualifications = slice.messages.allPersistentAnnotations().qualificationsFor(targetIid)
            val qualificationTypes = qualifications.map { it.detailInt(DetailKeys.QUALIFICATION_TYPE) }
            val qualificationGsm =
                slice.messages.gameStateMessages().single { gsm ->
                    gsm.persistentAnnotationsList.any { AnnotationType.Qualification in it.typeList }
                }
            val zoneTransferCategories =
                qualificationGsm.annotationsList
                    .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                    .map { it.detailString(DetailKeys.CATEGORY) }
            val persistentTypes = qualificationGsm.persistentAnnotationsList.map { it.typeList.first() }
            val firstQualification = persistentTypes.indexOf(AnnotationType.Qualification)
            val attachment = persistentTypes.indexOf(AnnotationType.Attachment)

            assertSoftly {
                qualificationTypes shouldContain QualificationType.CantAttack.wireValue
                qualificationTypes shouldContain QualificationType.CantBlock.wireValue
                qualifications.singleQualification(QualificationType.CantAttack).affectorId shouldNotBe targetIid
                qualifications.singleQualification(QualificationType.CantBlock).affectorId shouldNotBe targetIid
                qualificationGsm.annotationsList.any { AnnotationType.ResolutionStart in it.typeList } shouldBe true
                qualificationGsm.annotationsList.any { AnnotationType.ResolutionComplete in it.typeList } shouldBe true
                zoneTransferCategories shouldContain "Resolve"
                firstQualification shouldNotBe -1
                attachment shouldNotBe -1
                firstQualification shouldBeLessThan attachment
                qualifications.forEach { qualification ->
                    qualification.detail(DetailKeys.SOURCE_PARENT)?.type shouldBe KeyValuePairValueType.Int32
                    qualification.detail(DetailKeys.GRPID)?.type shouldBe KeyValuePairValueType.Int32
                    qualification.detail(DetailKeys.QUALIFICATION_SUBTYPE)?.type shouldBe KeyValuePairValueType.Int32
                    qualification.detail(DetailKeys.QUALIFICATION_TYPE)?.type shouldBe KeyValuePairValueType.Int32
                }
            }
        }
    })

private fun List<AnnotationInfo>.qualificationsFor(instanceId: Int): List<AnnotationInfo> =
    filter { AnnotationType.Qualification in it.typeList && it.affectedIdsList == listOf(instanceId) }

private fun List<AnnotationInfo>.singleQualification(type: QualificationType): AnnotationInfo =
    single { it.detailInt(DetailKeys.QUALIFICATION_TYPE) == type.wireValue }

private fun AnnotationInfo.detail(key: String) = detailsList.firstOrNull { it.key == key }

private fun AnnotationInfo.detailInt(key: String): Int = detailsList.first { it.key == key }.getValueInt32(0)

private fun AnnotationInfo.detailString(key: String): String = detailsList.first { it.key == key }.getValueString(0)
