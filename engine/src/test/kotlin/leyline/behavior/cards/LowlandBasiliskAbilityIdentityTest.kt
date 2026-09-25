package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.bundle.StateFrameInputCapture
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.battlefield
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class LowlandBasiliskAbilityIdentityTest :
    SessionTest({
        session(
            "damage trigger and delayed destroy retain one nonzero ability identity",
            puzzle =
                """
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Grizzly Bears
                humanlibrary=Plains;Plains
                aibattlefield=Lowland Basilisk
                ailibrary=Forest;Forest
                """.trimIndent(),
            aiScript =
                listOf(
                    ScriptedAction.Attack(listOf("Lowland Basilisk")),
                    ScriptedAction.PassPriority,
                ),
        ) {
            passUntil(maxPasses = 20) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()

            val basilisk = ai.getZone(forge.game.zone.ZoneType.Battlefield).cards.single { it.name == "Lowland Basilisk" }
            val attackerIid = ai.battlefield.iid("Lowland Basilisk")
            val blockerIid = human.battlefield.iid("Grizzly Bears")
            bridge.clearAbilityRegistryCacheForTesting()
            StateFrameInputCapture(bridge, "basilisk-identity", 1).captureNeutral(
                game = game(),
                gameStateId = 99,
                revealForSeat = null,
                events =
                    StateFrameInputCapture.Events.Supplied(
                        FrameEventLog(
                            listOf(
                                GameEvent.ZoneChanged(
                                    ForgeCardId(basilisk.id),
                                    Zone.Hand,
                                    Zone.Battlefield,
                                ),
                            ),
                        ),
                    ),
            )
            val start = messageSnapshot()

            declareBlockers(mapOf(blockerIid to attackerIid))
            passThroughCombat()

            val messages = messagesSince(start)
            val abilityIids =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceCreated)
                    .filter { it.affectorId == attackerIid }
                    .flatMap { it.affectedIdsList }
                    .toSet()
            val resolutions =
                messages
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter { it.affectorId in abilityIids }
                    .map { it.detailUint("grpid") }
            val abilityGrpIds =
                messages
                    .allGameObjects()
                    .filter { it.type == GameObjectType.Ability && it.instanceId in abilityIids }
                    .map { it.grpId }
                    .distinct()
            assertSoftly {
                resolutions shouldHaveSize 2
                resolutions.forEach { it shouldBeGreaterThan 0 }
                resolutions.distinct() shouldHaveSize 1
                abilityGrpIds shouldBe resolutions.distinct()
                human
                    .getZone(forge.game.zone.ZoneType.Graveyard)
                    .cards
                    .single()
                    .name shouldBe "Grizzly Bears"
            }
        }
    })
