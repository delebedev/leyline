package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.AbilityInfo
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class PendingTriggerVisualsTest :
    SessionTest({
        data class Candidate(
            val name: String,
            val sourceGrpId: Int,
            val sourceAbilityGrpId: Int,
            val cleanupAbilityGrpId: Int,
        )

        val ennis = Candidate("Ennis, Debate Moderator", 102473, 204302, 204550)
        val wiccan = Candidate("Wiccan, Rising Magician", 104978, 206147, 206386)
        val familiar = Candidate("Nine-Lives Familiar", 93779, 175823, 179839)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            for (candidate in listOf(ennis, wiccan, familiar)) {
                val grpId = TestCardRegistry.ensureCardRegistered(candidate.name)
                val data = TestCardRegistry.repo.findByGrpId(grpId)!!
                TestCardRegistry.repo.registerData(
                    data.copy(hiddenAbilityIds = listOf(candidate.cleanupAbilityGrpId to candidate.cleanupAbilityGrpId)),
                    candidate.name,
                )
                TestCardRegistry.repo.registerAbilityInfo(
                    candidate.cleanupAbilityGrpId,
                    AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2),
                )
            }
        }

        fun MatchFlowHarness.holderFor(candidate: Candidate): GameObjectInfo? =
            allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.gameObjectsList }
                .lastOrNull {
                    it.type == GameObjectType.TriggerHolder &&
                        it.objectSourceGrpId == candidate.sourceAbilityGrpId
                }

        fun MatchFlowHarness.assertVisuals(
            candidate: Candidate,
            affectedIid: Int,
            displaysAffectedCard: Boolean,
        ) {
            val holder = holderFor(candidate) ?: error("No TriggerHolder for ${candidate.name}")
            val persistent =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
            val holderRows = persistent.filter { it.affectorId == holder.instanceId }.distinctBy { it.id }
            assertSoftly(candidate.name) {
                holder.uniqueAbilitiesList.map { it.grpId } shouldContain candidate.cleanupAbilityGrpId
                holderRows
                    .single { AnnotationType.DelayedTriggerAffectees in it.typeList }
                    .also { ann ->
                        ann.affectedIdsList shouldContain affectedIid
                        ann.detailsList.none { it.key == "removes_from_zone" }.shouldBeTrue()
                    }
                holderRows
                    .single { AnnotationType.TemporaryPermanent in it.typeList }
                    .affectedIdsList shouldContain affectedIid
                holderRows.count { AnnotationType.DisplayCardUnderCard in it.typeList } shouldBe
                    if (displaysAffectedCard) 1 else 0
            }
        }

        session(
            "Ennis exile-and-return emits holder and visual triplet",
            puzzle =
                """
                [metadata]
                Name:Ennis pending trigger
                Goal:Win
                Turns:3

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ennis, Debate Moderator
                humanbattlefield=Plains;Plains;Grizzly Bears
                humanlibrary=Plains
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            val targetIid = human.battlefield.iid("Grizzly Bears")
            castSpellByName(ennis.name).shouldBeTrue()
            passUntil(maxPasses = 10) {
                allMessages.any { message ->
                    message.hasSelectTargetsReq() &&
                        message.selectTargetsReq.targetsList.any { target ->
                            target.targetsList.any { it.targetInstanceId == targetIid }
                        }
                }
            }.shouldBeTrue()
            selectTargets(listOf(targetIid))
            passUntil(maxPasses = 10) { holderFor(ennis) != null }.shouldBeTrue()
            val affectedIid =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .last { AnnotationType.DelayedTriggerAffectees in it.typeList }
                    .affectedIdsList
                    .single()
            assertVisuals(ennis, affectedIid, displaysAffectedCard = true)
        }

        session(
            "Wiccan exile-and-return emits holder and visual triplet",
            puzzle =
                """
                [metadata]
                Name:Wiccan pending trigger
                Goal:Win
                Turns:3

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock
                humanbattlefield=Wiccan, Rising Magician;Grizzly Bears;Mountain
                humanlibrary=Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            val targetIid = human.battlefield.iid("Grizzly Bears")
            passUntil(maxPasses = 10) {
                allMessages.any { message ->
                    message.hasSelectTargetsReq() &&
                        message.selectTargetsReq.targetsList.any { target ->
                            target.targetsList.any { it.targetInstanceId == targetIid }
                        }
                }
            }.shouldBeTrue()
            selectTargets(listOf(targetIid))
            passUntil(maxPasses = 10) { holderFor(wiccan) != null }.shouldBeTrue()
            val affectedIid =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .last { AnnotationType.DelayedTriggerAffectees in it.typeList }
                    .affectedIdsList
                    .single()
            assertVisuals(wiccan, affectedIid, displaysAffectedCard = true)
        }

        session(
            "Nine-Lives Familiar delayed return omits exile display relation",
            puzzle =
                """
                [metadata]
                Name:Nine-Lives pending trigger
                Goal:Win
                Turns:3

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock
                humanbattlefield=Nine-Lives Familiar|Counters:REVIVAL=8;Mountain
                humanlibrary=Swamp
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            val familiarIid = human.battlefield.iid(familiar.name)
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(familiarIid))
            passUntil(maxPasses = 15) { holderFor(familiar) != null }.shouldBeTrue()
            val affectedIid =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .last { AnnotationType.DelayedTriggerAffectees in it.typeList }
                    .affectedIdsList
                    .single()
            assertVisuals(familiar, affectedIid, displaysAffectedCard = false)
        }
    })
