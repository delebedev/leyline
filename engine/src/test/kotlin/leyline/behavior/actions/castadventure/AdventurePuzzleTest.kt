package leyline.behavior.actions.castadventure

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/**
 * Integration test for the full adventure lifecycle:
 *
 * 1. CastAdventure action available for adventure card in hand
 * 2. Submit CastAdventure → adventure goes on stack
 * 3. Adventure resolves → creates Rat tokens
 * 4. Card moves to exile
 * 5. Cast creature from exile
 * 6. Creature resolves to battlefield
 *
 */
class AdventurePuzzleTest :
    SessionTest({

        fun MatchFlowHarness.adventureCompanionIn(zoneId: Int): GameObjectInfo =
            accumulator.objects.values.single { obj ->
                obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == zoneId
            }

        session(
            "adventure lifecycle: cast adventure → tokens → exile → cast creature → battlefield",
            """
                [metadata]
                Name:Adventure Lifecycle
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:Full adventure card lifecycle.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=1

                humanhand=Ratcatcher Trainee
                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Swamp;Swamp;Swamp
                humanlibrary=Mountain
                aibattlefield=Forest
                ailibrary=Forest
                """.trimIndent(),
            validating = true,
        ) {
            phase() shouldBe "MAIN1"

            // --- Step 1: Verify CastAdventure action is available ---
            val traineeIid = human.hand.iid("Ratcatcher Trainee")
            val handCompanion = adventureCompanionIn(ZoneIds.P1_HAND)
            accumulator.zones
                .getValue(ZoneIds.P1_HAND)
                .objectInstanceIdsList shouldContain traineeIid
            accumulator.zones
                .getValue(ZoneIds.P1_HAND)
                .objectInstanceIdsList shouldNotContain handCompanion.instanceId

            // Look for CastAdventure in the latest ActionsAvailableReq
            val actionsMsg =
                allMessages
                    .asReversed()
                    .firstOrNull { it.hasActionsAvailableReq() }
            checkNotNull(actionsMsg) { "No ActionsAvailableReq found in messages" }

            val adventureActions =
                actionsMsg.actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.CastAdventure }
            adventureActions.shouldNotBeEmpty()

            val adventureAction = adventureActions.first()
            // instanceId should match the card in hand
            adventureAction.instanceId shouldBe traineeIid

            // --- Step 2: Submit CastAdventure action ---
            val castMsg =
                performAction {
                    actionType = ActionType.CastAdventure
                    instanceId = adventureAction.instanceId
                    grpId = adventureAction.grpId
                }
            val beforeCast = messageSnapshot()
            session.onPerformAction(submitWithGsId(castMsg))
            drainSink()
            val castFlow = messagesSince(beforeCast)
            val stackMessageIndex =
                castFlow.indexOfFirst { message ->
                    message.hasGameStateMessage() &&
                        message.gameStateMessage.gameObjectsList.any { obj ->
                            obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.STACK
                        }
                }
            check(stackMessageIndex >= 0) { "No state-only Adventure stack frame found" }
            val stackGsm = castFlow[stackMessageIndex].gameStateMessage
            val adventureStackCompanion =
                stackGsm.gameObjectsList.single { obj ->
                    obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.STACK
                }

            val exileMessageIndex =
                castFlow.indexOfFirst { message ->
                    message.hasGameStateMessage() &&
                        message.gameStateMessage.gameObjectsList.any { obj ->
                            obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.EXILE
                        }
                }
            check(exileMessageIndex > stackMessageIndex) { "Adventure exile frame did not follow its stack frame" }
            val exileGsm = castFlow[exileMessageIndex].gameStateMessage
            val visibleActionIndex =
                castFlow.indexOfFirst { message ->
                    message.hasActionsAvailableReq() && message.gameStateId > exileGsm.gameStateId
                }
            check(visibleActionIndex > exileMessageIndex) { "Visible priority window did not follow Adventure resolution" }

            assertSoftly {
                castFlow
                    .subList(stackMessageIndex, exileMessageIndex)
                    .count { it.hasActionsAvailableReq() } shouldBe 0
                exileGsm.annotationsList.count { AnnotationType.TokenCreated in it.typeList } shouldBe 2
                stackGsm.gameStateId shouldBeLessThan exileGsm.gameStateId
                exileGsm.gameStateId shouldBeLessThan castFlow[visibleActionIndex].gameStateId
                adventureStackCompanion.instanceId shouldNotBe handCompanion.instanceId
                accumulator.zones
                    .getValue(ZoneIds.STACK)
                    .objectInstanceIdsList shouldNotContain adventureStackCompanion.instanceId
            }

            // --- Step 3: Pass priority until adventure resolves (tokens appear) ---
            // Forge token name is "Rat Token" (from b_1_1_rat_noblock.txt)
            val tokensAppeared =
                passUntil(maxPasses = 15) {
                    human.getZone(ZoneType.Battlefield).cards.any { it.isToken && "Rat" in it.name }
                }
            tokensAppeared.shouldBeTrue()

            // Count Rat tokens — Pest Problem creates 2
            val ratTokens =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isToken && "Rat" in it.name }
            ratTokens.size shouldBe 2

            // --- Step 4: Verify card is in exile ---
            val inExile =
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Ratcatcher Trainee" }
            val exileCompanion = adventureCompanionIn(ZoneIds.EXILE)
            assertSoftly {
                inExile.shouldBeTrue()
                exileCompanion.instanceId shouldNotBe adventureStackCompanion.instanceId
                accumulator.zones
                    .getValue(ZoneIds.EXILE)
                    .objectInstanceIdsList shouldNotContain exileCompanion.instanceId
            }

            // --- Step 5: Cast creature from exile ---
            val beforeCreatureCast = messageSnapshot()
            val castCreature = castFromExile("Ratcatcher Trainee")
            val creatureCastMessages = messagesSince(beforeCreatureCast)
            val creatureStackCompanion =
                creatureCastMessages
                    .asSequence()
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList.asSequence() }
                    .first { obj -> obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.STACK }
            assertSoftly {
                castCreature.shouldBeTrue()
                creatureStackCompanion.instanceId shouldNotBe exileCompanion.instanceId
            }

            // --- Step 6: Pass until creature resolves to battlefield ---
            val creatureOnBattlefield =
                passUntil(maxPasses = 15) {
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .any { it.name == "Ratcatcher Trainee" }
                }
            val battlefieldCompanion = adventureCompanionIn(ZoneIds.BATTLEFIELD)
            assertSoftly {
                creatureOnBattlefield.shouldBeTrue()
                battlefieldCompanion.instanceId shouldBe creatureStackCompanion.instanceId
                accumulator.zones
                    .getValue(ZoneIds.BATTLEFIELD)
                    .objectInstanceIdsList shouldNotContain battlefieldCompanion.instanceId
            }

            // Final verification: Ratcatcher Trainee on battlefield
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Ratcatcher Trainee" }
                .shouldBeTrue()
        }
    })
