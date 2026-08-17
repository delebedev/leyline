package leyline.behavior.annotations.qualification

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.codes.QualificationType
import leyline.testkit.BoardTest
import leyline.testkit.detailIntList
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class CombatQualificationTest :
    BoardTest({
        test("Pacifism-style aura emits can't attack and can't block Qualifications") {
            val board =
                startWithBoard { _, human, ai ->
                    val target = addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                    val aura = addCard("Pacifism", human, ZoneType.Battlefield)
                    aura.attachToEntity(target, null, true)
                }
            val aura =
                board.game.humanPlayer.battlefield
                    .card("Pacifism")
            val target =
                board.game.aiPlayer.battlefield
                    .card("Grizzly Bears")

            val gsm = handshakeFull(board.game, board.bridge, board.counter.nextGsId())

            val targetIid = board.instanceId(target.id)
            val auraIid = board.instanceId(aura.id)
            val qualifications = gsm.qualificationsFor(targetIid)
            val qualificationTypes = qualifications.map { it.detailUint(DetailKeys.QUALIFICATION_TYPE) }

            assertSoftly {
                qualificationTypes shouldContain QualificationType.CantAttack.wireValue
                qualificationTypes shouldContain QualificationType.CantBlock.wireValue
            }

            val cantAttack = qualifications.singleQualification(QualificationType.CantAttack)
            val cantBlock = qualifications.singleQualification(QualificationType.CantBlock)

            assertSoftly {
                cantAttack.affectorId shouldBe auraIid
                cantBlock.affectorId shouldBe auraIid
                cantAttack.detailUint(DetailKeys.SOURCE_PARENT) shouldBe auraIid
                cantBlock.detailUint(DetailKeys.SOURCE_PARENT) shouldBe auraIid
                cantAttack.detailUint(DetailKeys.GRPID) shouldBeGreaterThan 0
                cantBlock.detailUint(DetailKeys.GRPID) shouldBe cantAttack.detailUint(DetailKeys.GRPID)
            }
        }

        test("unblockable creature emits can't be blocked Qualification") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Silent Hallcreeper", human, ZoneType.Hand)
                }
            val hallcreeper =
                board.game.humanPlayer.hand
                    .card("Silent Hallcreeper")

            val gsm = board.snapshotDiff { board.game.action.moveToPlay(hallcreeper, null, AbilityKey.newMap()) }

            val hallcreeperIid = board.instanceId(hallcreeper.id)
            val qualification = gsm.qualificationsFor(hallcreeperIid).singleQualification(QualificationType.CantBeBlocked)

            assertSoftly {
                qualification.affectorId shouldBe hallcreeperIid
                qualification.detailUint(DetailKeys.SOURCE_PARENT) shouldBe hallcreeperIid
                qualification.detailUint(DetailKeys.GRPID) shouldBeGreaterThan 0
            }
        }

        test("block-only restriction emits can't block object list") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Wanderlight Spirit", human, ZoneType.Hand)
                    addCard("Coral Merfolk", ai, ZoneType.Battlefield)
                    addCard("Serra Angel", ai, ZoneType.Battlefield)
                }
            val spirit =
                board.game.humanPlayer.hand
                    .card("Wanderlight Spirit")
            val merfolk =
                board.game.aiPlayer.battlefield
                    .card("Coral Merfolk")

            val gsm = board.snapshotDiff { board.game.action.moveToPlay(spirit, null, AbilityKey.newMap()) }

            val spiritIid = board.instanceId(spirit.id)
            val merfolkIid = board.instanceId(merfolk.id)

            val qualification = gsm.qualificationsFor(spiritIid).singleQualification(QualificationType.CantBlock)

            qualification.detailIntList(DetailKeys.CANT_BLOCK_OBJECTS) shouldBe listOf(merfolkIid)
        }

        test("blocker-specific evasion emits can't be blocked by object list") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Juggernaut", human, ZoneType.Hand)
                    addCard("Gleaming Barrier", ai, ZoneType.Battlefield)
                    addCard("Coral Merfolk", ai, ZoneType.Battlefield)
                }
            val juggernaut =
                board.game.humanPlayer.hand
                    .card("Juggernaut")
            val wall =
                board.game.aiPlayer.battlefield
                    .card("Gleaming Barrier")

            val gsm = board.snapshotDiff { board.game.action.moveToPlay(juggernaut, null, AbilityKey.newMap()) }

            val juggernautIid = board.instanceId(juggernaut.id)
            val wallIid = board.instanceId(wall.id)

            val qualification = gsm.qualificationsFor(juggernautIid).singleQualification(QualificationType.CantBeBlocked)

            qualification.detailIntList(DetailKeys.CANT_BE_BLOCKED_BY_OBJECTS) shouldBe listOf(wallIid)
        }

        test("keyword evasion does not emit combat restriction Qualification") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Serra Angel", human, ZoneType.Battlefield)
                    addCard("Coral Merfolk", ai, ZoneType.Battlefield)
                }
            val serra =
                board.game.humanPlayer.battlefield
                    .card("Serra Angel")

            val gsm = handshakeFull(board.game, board.bridge, board.counter.nextGsId())

            val serraIid = board.instanceId(serra.id)
            val qualificationTypes = gsm.qualificationsFor(serraIid).map { it.detailUint(DetailKeys.QUALIFICATION_TYPE) }

            qualificationTypes shouldBe emptyList()
        }
    })

private fun wotc.mtgo.gre.external.messaging.Messages.GameStateMessage.qualificationsFor(instanceId: Int): List<AnnotationInfo> =
    persistentAnnotationsList.filter { AnnotationType.Qualification in it.typeList && it.affectedIdsList == listOf(instanceId) }

private fun List<AnnotationInfo>.singleQualification(type: QualificationType): AnnotationInfo =
    single { it.detailUint(DetailKeys.QUALIFICATION_TYPE) == type.wireValue }

private val forge.game.Game.humanPlayer get() = players[0]

private val forge.game.Game.aiPlayer get() = players[1]
