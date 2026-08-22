package leyline.game.snapshot

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
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
 * Discover coverage is intentionally absent. Discover's ETB-if-cast trigger
 * lifecycle is shorter than the harness's drain-and-pass cycle: Forge fires
 * the trigger, runs the DiscoverEffect, and `HeadlessMatch.drainSink`
 * auto-accepts the resulting OptionalActionMessage all within one engine
 * tick. The snapshot-after-pass window never sees the trigger on the stack.
 * Tracked separately for a unit-test layer (mockk SA + cardData) that can
 * call [StackAbilityGrpIdResolver.resolveEntryAbilityGrpId] directly.
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

            val bbeGrpId = cardGrpId("Bloodbraid Elf")!!
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

            assertSoftly {
                cascadeEntry.grpId shouldBe KeywordAbilityIds.CASCADE
                cascadeEntry.grpId shouldBe 86
                cascadeEntry.objectSourceGrpId shouldBe bbeGrpId
                triggeringSource.grpId shouldBe bbeGrpId
                triggeringSource.zoneId shouldBe ZoneIds.STACK
                triggeringObject.detailInt("source_zone") shouldBe ZoneIds.STACK
                require(cascadeEntry.grpId != cascadeEntry.objectSourceGrpId) {
                    "ability grpId and sourceCardGrpId collapsed back to the same value"
                }
            }
        }
    })
