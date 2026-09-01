package leyline.game.snapshot

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

private val PUZZLE =
    """
    [metadata]
    Name:Cascade Bloodbraid Elf
    Goal:Survive
    Turns:5
    Difficulty:Easy
    Description:Cast Bloodbraid Elf to trigger Cascade. Library top first nonland is Llanowar Elves (MV 1) — accept the may-cast and it enters battlefield for free without needing a target.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Bloodbraid Elf
    humanbattlefield=Mountain;Mountain;Mountain;Forest;Forest
    humanlibrary=Llanowar Elves;Forest;Forest;Mountain;Mountain;Forest;Mountain;Forest;Mountain;Forest;Mountain;Forest
    aibattlefield=Grizzly Bears
    ailibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
    """.trimIndent()

private val DISCOVER_PUZZLE =
    """
    [metadata]
    Name:Discover Geological Appraiser
    Goal:Survive
    Turns:5
    Difficulty:Easy
    Description:Cast Geological Appraiser to discover and cast Llanowar Elves.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Geological Appraiser
    humanbattlefield=Mountain;Mountain;Mountain;Mountain
    humanlibrary=Llanowar Elves;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    aibattlefield=Grizzly Bears
    ailibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
    """.trimIndent()

/**
 * End-to-end coverage for the trigger-ability projection on the Stack zone.
 *
 * Locks in the contract that the `Ability` GameObject (zone 27) carries:
 *  - `grpId` = the **ability row id** (Cascade=86, per-card Discover row, etc.)
 *  - `objectSourceGrpId` = the **source card's grpId** (the host permanent)
 *
 * These two fields used to collapse to the same value. The
 * [StackAbilityGrpIdResolver.resolveEntryAbilityGrpId] resolver and the
 * [leyline.game.mapping.ZoneMapper.addStackAbilitiesFromSnapshot] spell-vs-trigger
 * filter together produce the decoupled shape — this Cascade test exercises
 * both via the puzzle harness so a regression in either path fails here, in
 * seconds.
 *
 * Discover's short trigger lifecycle does not expose its stack object through
 * the snapshot-after-pass window, but the forced pre-prompt frame still owns
 * its free-cast permission row.
 */
class CascadeDiscoverProjectionTest :
    SessionTest({

        tags(BoardTag)

        session(
            "Cascade trigger StackEntry resolves grpId=86 and source-card grpId independently",
            puzzle = PUZZLE,
        ) {
            val before = messageSnapshot()
            val cast = castSpellByName("Bloodbraid Elf")
            cast shouldBe true

            val bbeGrpId = bridge.cardRepository.findGrpIdByName("Bloodbraid Elf")!!
            val projectedStates = messagesSince(before).gameStateMessages()
            val cascadeEntry =
                projectedStates
                    .flatMap { it.gameObjectsList }
                    .filter {
                        it.type == GameObjectType.Ability &&
                            it.grpId == KeywordAbilityIds.CASCADE &&
                            it.objectSourceGrpId == bbeGrpId
                    }.distinctBy { it.instanceId }
                    .single()
            val triggeringObject =
                projectedStates
                    .flatMap { it.persistentAnnotationsList }
                    .filter { AnnotationType.TriggeringObject in it.typeList }
                    .distinctBy { it.id }
                    .single()
            val triggeringSource =
                projectedStates
                    .flatMap { it.gameObjectsList }
                    .first { it.instanceId == triggeringObject.affectedIdsList.single() }
            val castingTimeOption =
                projectedStates
                    .flatMap { it.persistentAnnotationsList }
                    .filter { AnnotationType.CastingTimeOption in it.typeList }
                    .distinctBy { it.id }
                    .single()
            val castableCard =
                projectedStates
                    .flatMap { it.gameObjectsList }
                    .first { it.instanceId == castingTimeOption.affectedIdsList.single() }
            val decline =
                allMessages
                    .last { it.hasActionsAvailableReq() && it.prompt.promptId == PromptIds.FREE_CAST_FROM_REVEAL }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Pass }
            submitAction(decline)

            assertSoftly {
                cascadeEntry.grpId shouldBe KeywordAbilityIds.CASCADE
                cascadeEntry.grpId shouldBe 86
                cascadeEntry.objectSourceGrpId shouldBe bbeGrpId
                triggeringSource.grpId shouldBe bbeGrpId
                triggeringSource.zoneId shouldBe ZoneIds.STACK
                triggeringObject.detailInt("source_zone") shouldBe ZoneIds.STACK
                castingTimeOption.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                castingTimeOption.detailInt("alternateCostGrpId") shouldBe 149
                castingTimeOption.detailInt("castAbilityGrpId") shouldBe KeywordAbilityIds.CASCADE
                castableCard.grpId shouldBe bridge.cardRepository.findGrpIdByName("Llanowar Elves")
                castableCard.zoneId shouldBe ZoneIds.EXILE
                castingTimeOption.affectedIdsList shouldBe listOf(castingTimeOption.affectorId)
                castingTimeOption.affectedIdsList shouldBe listOf(castableCard.instanceId)
                castingTimeOption.affectedIdsList shouldNotBe listOf(triggeringSource.instanceId)
                messagesSince(before)
                    .gameStateMessages()
                    .flatMap { it.diffDeletedPersistentAnnotationIdsList } shouldContain castingTimeOption.id
                require(cascadeEntry.grpId != cascadeEntry.objectSourceGrpId) {
                    "ability grpId and sourceCardGrpId collapsed back to the same value"
                }
            }
        }

        session(
            "Discover free-cast permission carries its per-card ability identity",
            puzzle = DISCOVER_PUZZLE,
        ) {
            val before = messageSnapshot()
            castSpellByName("Geological Appraiser") shouldBe true

            val projectedStates = messagesSince(before).gameStateMessages()
            val castingTimeOption =
                projectedStates
                    .flatMap { it.persistentAnnotationsList }
                    .filter { AnnotationType.CastingTimeOption in it.typeList }
                    .distinctBy { it.id }
                    .single()
            val castableCard =
                projectedStates
                    .flatMap { it.gameObjectsList }
                    .first { it.instanceId == castingTimeOption.affectedIdsList.single() }
            val decline =
                allMessages
                    .last { it.hasActionsAvailableReq() && it.prompt.promptId == PromptIds.FREE_CAST_FROM_REVEAL }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Pass }
            submitAction(decline)

            assertSoftly {
                castingTimeOption.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                castingTimeOption.detailInt("alternateCostGrpId") shouldBe 149
                castingTimeOption.detailInt("castAbilityGrpId") shouldBe 169_621
                castableCard.grpId shouldBe bridge.cardRepository.findGrpIdByName("Llanowar Elves")
                castableCard.zoneId shouldBe ZoneIds.EXILE
                castingTimeOption.affectedIdsList shouldBe listOf(castingTimeOption.affectorId)
                messagesSince(before)
                    .gameStateMessages()
                    .flatMap { it.diffDeletedPersistentAnnotationIdsList } shouldContain castingTimeOption.id
            }
        }
    })
