package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.conformance.MatchFlowHarness
import leyline.conformance.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType

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
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("adventure lifecycle: cast adventure → tokens → exile → cast creature → battlefield") {
            val pzl = """
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
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)
            h.phase() shouldBe "MAIN1"

            val human = h.game().registeredPlayers.first()

            // --- Step 1: Verify CastAdventure action is available ---
            val trainee = human.getZone(ZoneType.Hand).cards
                .first { it.name == "Ratcatcher Trainee" }
            val traineeIid = h.bridge.getOrAllocInstanceId(ForgeCardId(trainee.id)).value

            // Look for CastAdventure in the latest ActionsAvailableReq
            val actionsMsg = h.allMessages.asReversed()
                .firstOrNull { it.hasActionsAvailableReq() }
            checkNotNull(actionsMsg) { "No ActionsAvailableReq found in messages" }

            val adventureActions = actionsMsg.actionsAvailableReq.actionsList
                .filter { it.actionType == ActionType.CastAdventure }
            adventureActions.shouldNotBeEmpty()

            val adventureAction = adventureActions.first()
            // instanceId should match the card in hand
            adventureAction.instanceId shouldBe traineeIid

            // --- Step 2: Submit CastAdventure action ---
            val castMsg = performAction {
                actionType = ActionType.CastAdventure
                instanceId = adventureAction.instanceId
                grpId = adventureAction.grpId
            }
            h.session.onPerformAction(castMsg)
            h.drainSink()

            // --- Step 3: Pass priority until adventure resolves (tokens appear) ---
            // Forge token name is "Rat Token" (from b_1_1_rat_noblock.txt)
            val tokensAppeared = h.passUntil(maxPasses = 15) {
                human.getZone(ZoneType.Battlefield).cards.any { it.isToken && "Rat" in it.name }
            }
            tokensAppeared.shouldBeTrue()

            // Count Rat tokens — Pest Problem creates 2
            val ratTokens = human.getZone(ZoneType.Battlefield).cards
                .filter { it.isToken && "Rat" in it.name }
            ratTokens.size shouldBe 2

            // --- Step 4: Verify card is in exile ---
            val inExile = human.getZone(ZoneType.Exile).cards
                .any { it.name == "Ratcatcher Trainee" }
            inExile.shouldBeTrue()

            // --- Step 5: Cast creature from exile ---
            h.castFromExile("Ratcatcher Trainee").shouldBeTrue()

            // --- Step 6: Pass until creature resolves to battlefield ---
            val creatureOnBattlefield = h.passUntil(maxPasses = 15) {
                human.getZone(ZoneType.Battlefield).cards
                    .any { it.name == "Ratcatcher Trainee" }
            }
            creatureOnBattlefield.shouldBeTrue()

            // Final verification: Ratcatcher Trainee on battlefield
            human.getZone(ZoneType.Battlefield).cards
                .any { it.name == "Ratcatcher Trainee" }.shouldBeTrue()
        }
    })
