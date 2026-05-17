package leyline.mechanics.paradigm

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

private const val PARADIGM_COPY_TRIGGER = 205572
private const val FREE_CAST = 149
private const val GERMINATION_PRACTICUM = 102608
private const val DECORUM_DISSERTATION = 102537
private const val DECORUM_TARGETING = 2135

private val GERMINATION_PUZZLE =
    """
    [metadata]
    Name:Paradigm - Germination Practicum
    Goal:Resolve a Paradigm spell, then cast its recurring copy.
    Turns:6

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germination Practicum
    humanbattlefield=Forest;Forest;Forest;Forest;Forest;Grizzly Bears
    humanlibrary=Forest;Forest;Forest;Forest;Forest
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val DECORUM_PUZZLE =
    """
    [metadata]
    Name:Paradigm - Decorum Dissertation
    Goal:Copy a targeted Paradigm spell and target a player.
    Turns:6

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Decorum Dissertation
    humanbattlefield=Swamp;Swamp;Swamp;Swamp;Swamp
    humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class ParadigmLifecycleTest :
    SessionTest({
        test("untargeted Paradigm original self-exiles, creates Main1 trigger, and casts copy for free") {
            startPuzzleRaw(GERMINATION_PUZZLE)

            harness.resolveSpell("Germination Practicum").shouldBeTrue()
            human
                .getZone(ZoneType.Exile)
                .cards
                .any { it.name == "Germination Practicum" }
                .shouldBeTrue()

            val sawFreeCopyCast =
                harness.passUntil(maxPasses = 30) {
                    gsms().flatMap { it.annotationsList }.any { it.isParadigmCopyCastAction() }
                }
            sawFreeCopyCast.shouldBeTrue()
            val sawCopySelfExile =
                harness.passUntil(maxPasses = 30) {
                    gsms()
                        .flatMap { it.annotationsList }
                        .count { it.isStackToExileParadigmTransfer() }
                        .let { it >= 2 }
                }
            sawCopySelfExile.shouldBeTrue()

            val allGsms = gsms()
            assertSoftly {
                allGsms
                    .flatMap { it.annotationsList }
                    .count { it.isStackToExileParadigmTransfer() }
                    .let { it >= 2 }
                    .shouldBeTrue()

                allGsms
                    .flatMap { it.gameObjectsList }
                    .firstOrNull {
                        it.type == GameObjectType.Ability &&
                            it.grpId == PARADIGM_COPY_TRIGGER &&
                            it.objectSourceGrpId == GERMINATION_PRACTICUM &&
                            it.zoneId == ZoneIds.STACK
                    } shouldNotBe null

                allGsms
                    .flatMap { it.annotationsList }
                    .firstOrNull { it.isParadigmCopyCastAction() } shouldNotBe null
            }
        }

        test("targeted Paradigm copy prompt uses copied card source and targeting metadata") {
            startPuzzleRaw(DECORUM_PUZZLE)

            harness.castSpellByName("Decorum Dissertation").shouldBeTrue()
            val originalTargetSourceId = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq.sourceId
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()
            human
                .getZone(ZoneType.Exile)
                .cards
                .any { it.name == "Decorum Dissertation" }
                .shouldBeTrue()

            val sawCopyTargetPrompt =
                harness.passUntil(maxPasses = 30) {
                    allMessages.any { it.hasSelectTargetsReq() && it.selectTargetsReq.sourceId != originalTargetSourceId }
                }
            sawCopyTargetPrompt.shouldBeTrue()

            val copyTargetReq =
                allMessages.last { it.hasSelectTargetsReq() && it.selectTargetsReq.sourceId != originalTargetSourceId }.selectTargetsReq
            assertSoftly {
                copyTargetReq.sourceId shouldNotBe 0
                copyTargetReq.abilityGrpId shouldBe DECORUM_DISSERTATION
                val copySourceObjects =
                    gsms()
                        .flatMap { it.gameObjectsList }
                        .filter { it.instanceId == copyTargetReq.sourceId }
                copySourceObjects
                    .firstOrNull {
                        it.instanceId == copyTargetReq.sourceId &&
                            it.type == GameObjectType.Card &&
                            it.grpId == DECORUM_DISSERTATION &&
                            it.zoneId == ZoneIds.STACK &&
                            it.isCopy
                    } shouldNotBe null
                val target = copyTargetReq.targetsList.first()
                target.prompt.parametersList
                    .first { it.parameterName == "CardId" }
                    .numberValue shouldBe copyTargetReq.sourceId
                target.targetingAbilityGrpId shouldBe DECORUM_TARGETING
                gsms()
                    .flatMap { it.persistentAnnotationsList }
                    .firstOrNull {
                        it.typeList.contains(AnnotationType.CastingTimeOption) &&
                            it.affectedIdsList.contains(copyTargetReq.sourceId) &&
                            it.detailInt("type") == 13 &&
                            it.detailInt("alternateCostGrpId") == FREE_CAST &&
                            it.detailInt("castAbilityGrpId") == PARADIGM_COPY_TRIGGER
                    } shouldNotBe null
            }

            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()
            val sawCopySelfExile =
                harness.passUntil(maxPasses = 30) {
                    gsms()
                        .flatMap { it.annotationsList }
                        .count { it.isStackToExileParadigmTransfer() }
                        .let { it >= 2 }
                }

            assertSoftly {
                sawCopySelfExile.shouldBeTrue()
                gsms()
                    .flatMap { it.annotationsList }
                    .firstOrNull { it.isParadigmCopyCastAction() } shouldNotBe null
                gsms()
                    .flatMap { it.annotationsList }
                    .count { it.isStackToExileParadigmTransfer() }
                    .let { it >= 2 }
                    .shouldBeTrue()
            }
        }
    })

private fun List<GREToClientMessage>.gsms(): List<GameStateMessage> =
    mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }

private fun SessionTest.gsms(): List<GameStateMessage> = allMessages.gsms()

private fun AnnotationInfo.isStackToExileParadigmTransfer(): Boolean =
    typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
        detailInt("zone_src") == ZoneIds.STACK &&
        detailInt("zone_dest") == ZoneIds.EXILE &&
        detailString("category") == "Exile"

private fun AnnotationInfo.isParadigmCopyCastAction(): Boolean =
    typeList.contains(AnnotationType.UserActionTaken) &&
        detailIntOrNull("actionType") == ActionType.Cast.number &&
        detailIntOrNull("alternativeGrpId") == FREE_CAST &&
        detailIntOrNull("abilityGrpId") == PARADIGM_COPY_TRIGGER

private fun AnnotationInfo.detailIntOrNull(key: String): Int? =
    detailsList.firstOrNull { it.key == key }?.takeIf { it.valueInt32Count > 0 }?.getValueInt32(0)
