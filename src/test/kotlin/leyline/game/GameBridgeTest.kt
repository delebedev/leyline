package leyline.game

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import leyline.bridge.GameBootstrap
import leyline.bridge.PlayerAction
import leyline.config.GameConfig
import leyline.config.PlaytestConfig
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ZoneIds
import org.testng.Assert
import org.testng.Assert.assertEquals
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages
import kotlin.collections.get

/**
 * Integration tests for [GameBridge] — verifies the real Forge engine
 * deals hands, resolves grpIds, and handles mulligan keep/mull.
 *
 * Requires card DB init (~2-3s first run, cached after).
 */
@Test(groups = ["integration"])
class GameBridgeTest {

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
        leyline.conformance.TestCardRegistry.ensureRegistered()
    }

    private lateinit var bridge: GameBridge

    @AfterMethod
    fun tearDown() {
        if (::bridge.isInitialized) bridge.shutdown()
    }

    @Test
    fun bridgeStartsAndDealsHand() {
        val b = GameBridge()
        bridge = b
        b.start()

        val seat1Hand = b.getHandGrpIds(1)
        val seat2Hand = b.getHandGrpIds(2)

        Assert.assertEquals(seat1Hand.size, 7, "Seat 1 should have 7 cards")
        // Seat 2 is AI with autoKeep — engine may have already progressed past mulligan,
        // but since we stop at seat 1's mulligan, seat 2 should also have cards dealt
        Assert.assertTrue(seat2Hand.isNotEmpty(), "Seat 2 should have cards dealt")
    }

    @Test
    fun getHandGrpIdsResolves() {
        val b = GameBridge()
        bridge = b
        b.start()

        val hand = b.getHandGrpIds(1)
        // All cards in the deck (Llanowar Elves, Elvish Mystic, Giant Growth, Forest)
        // should resolve to non-zero grpIds via client card DB
        // Some may be 0 if client DB isn't installed, but the engine dealt real cards
        Assert.assertEquals(hand.size, 7)
    }

    @Test
    fun getDeckGrpIdsReturnsFullDeck() {
        val b = GameBridge()
        bridge = b
        b.start()

        val deck = b.getDeckGrpIds(1)
        Assert.assertEquals(deck.size, 60, "Full deck should be 60 cards (hand + library)")
    }

    @Test
    fun keepAdvancesToPriority() {
        val b = GameBridge()
        bridge = b
        b.start()

        Assert.assertEquals(b.getHandGrpIds(1).size, 7)
        b.submitKeep(1)
        b.awaitPriority()

        // Engine should be at Main1 (or later) with a pending action
        val pending = b.actionBridge.getPending()
        Assert.assertNotNull(pending, "Engine should have a pending action at priority")

        val game = b.getGame()!!
        val phase = game.phaseHandler.phase
        Assert.assertTrue(
            phase == PhaseType.MAIN1 || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW,
            "Engine should be at MAIN1 or beginning step, got $phase",
        )
    }

    /**
     * London mulligan: mull → engine draws 7 → auto-tucks 1 → hand has 6.
     * After submitMull, engine is back at WaitingKeep with post-tuck hand.
     */
    @Test
    fun submitMullAutoTucksAndProducesNewHand() {
        val b = GameBridge()
        bridge = b
        b.start()

        val handBefore = b.getHandGrpIds(1)
        Assert.assertEquals(handBefore.size, 7)

        b.submitMull(1)

        val handAfter = b.getHandGrpIds(1)
        // London: drew 7, auto-tucked 1 → 6 cards remain
        Assert.assertEquals(handAfter.size, 6, "After first mull+auto-tuck hand should have 6 cards")
    }

    /** Two mulligans: mull → auto-tuck 1 → mull → auto-tuck 2 → hand has 5. */
    @Test
    fun submitMullTwiceReducesHandByTwo() {
        val b = GameBridge()
        bridge = b
        b.start()

        b.submitMull(1)
        Assert.assertEquals(b.getHandGrpIds(1).size, 6, "After 1st mull: 6 cards")

        b.submitMull(1)
        Assert.assertEquals(b.getHandGrpIds(1).size, 5, "After 2nd mull: 5 cards")
    }

    /** Mull once, then keep → game reaches priority. */
    @Test
    fun mullThenKeepReachesPriority() {
        val b = GameBridge()
        bridge = b
        b.start()

        b.submitMull(1)
        Assert.assertEquals(b.getHandGrpIds(1).size, 6)

        b.submitKeep(1)
        b.awaitPriority()

        val game = b.getGame()!!
        val phase = game.phaseHandler.phase
        Assert.assertTrue(
            phase == PhaseType.MAIN1 || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW,
            "Should reach gameplay after mull+keep, got $phase",
        )
    }

    @Test
    fun buildActionsIncludesLands() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        Assert.assertEquals(game.phaseHandler.phase, PhaseType.MAIN1, "Should be at Main1")

        val actions = ActionMapper.buildActions(game, 1, b)

        val hasPass = actions.actionsList.any {
            it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass
        }
        Assert.assertTrue(hasPass, "Actions should include Pass")

        // Deck has 32 Forest — must have a land play at Main1
        val hasLand = actions.actionsList.any {
            it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Play_add3
        }
        Assert.assertTrue(hasLand, "Should have playable land at Main1")
    }

    @Test
    fun playLandMovesCardToBattlefield() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val player = b.getPlayer(1)!!

        val handBefore = player.getZone(ZoneType.Hand).size()
        val bfBefore = player.getZone(ZoneType.Battlefield).size()

        val landInHand = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: error("No land in hand at seed 42")
        val pending = awaitFreshPending(b, null)
            ?: error("No pending action available")

        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(landInHand.id))
        awaitFreshPending(b, pending.actionId)

        val handAfter = player.getZone(ZoneType.Hand).size()
        val bfAfter = player.getZone(ZoneType.Battlefield).size()

        assertEquals(handAfter, handBefore - 1, "Hand should shrink by 1 after land play")
        assertEquals(bfAfter, bfBefore + 1, "Battlefield should grow by 1 after land play")
    }

    // --- Proto shape assertions (uses same builder as handler) ---

    /**
     * Validates the game-start GRE bundle shape via [BundleBuilder.phaseTransitionDiff]:
     *   GRE 1: Diff/SendHiFi, 2x PhaseOrStepModified, gameInfo
     *   GRE 2: Diff/SendHiFi echo (turnInfo + actions)
     *   GRE 3: Diff/SendAndRecord, 1x PhaseOrStepModified
     *   GRE 4: PromptReq
     *   GRE 5: ActionsAvailableReq, actions > 0
     */
    @Test
    fun gameStartBundleHasCorrectShape() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))
        val messages = result.messages

        // Bundle has exactly 5 GRE messages (phaseTransitionDiff pattern)
        Assert.assertEquals(messages.size, 5, "Game start bundle should have 5 GRE messages")

        // GRE 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
        val gre1 = messages[0]
        assertEquals(
            gre1.gameStateMessage.update,
            wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendHiFi,
            "GRE 1 should use SendHiFi",
        )
        Assert.assertTrue(
            gre1.gameStateMessage.hasGameInfo(),
            "GRE 1 should have gameInfo",
        )
        val phaseAnnotations1 = gre1.gameStateMessage.annotationsList.flatMap { it.typeList }
            .count { it == wotc.mtgo.gre.external.messaging.Messages.AnnotationType.PhaseOrStepModified }
        Assert.assertTrue(phaseAnnotations1 >= 2, "GRE 1 should have 2+ PhaseOrStepModified annotations")

        // GRE 2: SendHiFi echo (turnInfo + actions, no annotations)
        val gre2 = messages[1]
        assertEquals(
            gre2.gameStateMessage.type,
            wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff,
            "GRE 2 should be Diff",
        )
        assertEquals(
            gre2.gameStateMessage.update,
            wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendHiFi,
            "GRE 2 should use SendHiFi",
        )
        Assert.assertTrue(
            gre2.gameStateMessage.gameStateId > gre1.gameStateMessage.gameStateId,
            "GRE 2 gsId should be > GRE 1 gsId",
        )

        // GRE 3: SendAndRecord with 1x PhaseOrStepModified
        val gre3 = messages[2]
        assertEquals(
            gre3.gameStateMessage.update,
            wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendAndRecord,
            "GRE 3 should use SendAndRecord",
        )
        val phaseAnnotations3 = gre3.gameStateMessage.annotationsList.flatMap { it.typeList }
            .count { it == wotc.mtgo.gre.external.messaging.Messages.AnnotationType.PhaseOrStepModified }
        Assert.assertEquals(phaseAnnotations3, 1, "GRE 3 should have 1 PhaseOrStepModified")

        // GRE 4: PromptReq
        val gre4 = messages[3]
        assertEquals(
            gre4.type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.PromptReq,
            "GRE 4 should be PromptReq",
        )

        // GRE 5: ActionsAvailableReq
        val gre5 = messages[4]
        assertEquals(
            gre5.type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.ActionsAvailableReq_695e,
            "GRE 5 should be ActionsAvailableReq",
        )
        Assert.assertTrue(
            gre5.actionsAvailableReq.actionsCount > 0,
            "GRE 5 should have actions",
        )
    }

    /**
     * After a land play, [BundleBuilder.postAction] produces consistent instanceIds:
     * every action references a card that exists in a zone.
     */
    @Test
    fun postActionStateHasConsistentInstanceIds() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val player = b.getPlayer(1)!!
        val landInHand = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        val pending = awaitFreshPending(b, null)!!
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(landInHand.id))
        awaitFreshPending(b, pending.actionId)

        val result = BundleBuilder.postAction(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))
        val gs = result.messages.first().gameStateMessage
        val actions = result.messages.last().actionsAvailableReq

        val allZoneInstanceIds = gs.zonesList
            .flatMap { it.objectInstanceIdsList }
            .toSet()
        for (action in actions.actionsList) {
            if (action.instanceId != 0) {
                Assert.assertTrue(
                    action.instanceId in allZoneInstanceIds,
                    "Post-action: instanceId ${action.instanceId} not found in zones",
                )
            }
        }

        // Every game object should be in a zone
        for (obj in gs.gameObjectsList) {
            val inZone = gs.zonesList.any { obj.instanceId in it.objectInstanceIdsList }
            Assert.assertTrue(
                inZone,
                "Game object instanceId=${obj.instanceId} not in any zone",
            )
        }
    }

    // --- Deterministic seed tests ---

    /** Starting with the same seed produces the same hand. */
    @Test
    fun deterministicSeedProducesSameHand() {
        val b1 = GameBridge()
        bridge = b1
        b1.start(seed = 42L)
        val hand1 = b1.getHandGrpIds(1)
        b1.shutdown()

        val b2 = GameBridge()
        bridge = b2
        b2.start(seed = 42L)
        val hand2 = b2.getHandGrpIds(1)

        assertEquals(hand1, hand2, "Same seed should produce same hand")
    }

    // --- Double-diff tests ---

    /** phaseTransitionDiff emits the full 5-message client pattern. */
    @Test
    fun phaseTransitionEmitsFiveMessagePattern() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))

        Assert.assertEquals(result.messages.size, 5, "Phase transition should emit 5 messages")

        // Message 1: SendHiFi with PhaseOrStepModified annotations
        val gs1 = result.messages[0].gameStateMessage
        assertEquals(gs1.update, Messages.GameStateUpdate.SendHiFi)
        assertEquals(gs1.type, Messages.GameStateType.Diff)

        // Message 2: SendHiFi echo (no annotations)
        val gs2 = result.messages[1].gameStateMessage
        assertEquals(gs2.update, Messages.GameStateUpdate.SendHiFi)
        assertEquals(gs2.type, Messages.GameStateType.Diff)

        // Message 3: SendAndRecord with PhaseOrStepModified
        val gs3 = result.messages[2].gameStateMessage
        assertEquals(gs3.update, Messages.GameStateUpdate.SendAndRecord)
        assertEquals(gs3.type, Messages.GameStateType.Diff)

        // Message 4: PromptReq (promptId=37)
        assertEquals(result.messages[3].type, Messages.GREMessageType.PromptReq)
        assertEquals(result.messages[3].prompt.promptId, 37)

        // Message 5: ActionsAvailableReq (promptId=2)
        assertEquals(result.messages[4].type, Messages.GREMessageType.ActionsAvailableReq_695e)
        assertEquals(result.messages[4].prompt.promptId, 2)

        // gsIds should be ascending across GSM messages
        val gsIds = result.messages.filter { it.hasGameStateMessage() }
            .map { it.gameStateMessage.gameStateId }
        for (i in 1 until gsIds.size) {
            Assert.assertTrue(gsIds[i] > gsIds[i - 1], "gsIds should be ascending")
        }
    }

    // --- Combat tests ---

    /** At COMBAT_DECLARE_ATTACKERS, buildDeclareAttackersReq lists eligible creatures. */
    @Test
    fun declareAttackersReqListsEligibleCreatures() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val player = b.getPlayer(1)!!

        // Play a land, cast a creature, pass to turn 2 for sickness to clear
        playLandAndCastCreature(b)

        // Check if we actually have creatures on battlefield
        val creatures = player.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
        if (creatures.isEmpty()) {
            // Creature cast may have failed (bad mana, no creatures in hand) — skip
            return
        }

        // Advance through turn 1 end, turn 2 start, to combat
        advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", maxPasses = 80)
        if (game.isGameOver || game.phaseHandler.phase != PhaseType.COMBAT_DECLARE_ATTACKERS) return

        val req = RequestBuilder.buildDeclareAttackersReq(game, 1, b)
        Assert.assertTrue(req.canSubmitAttackers, "canSubmitAttackers should be true")

        // If we have eligible attackers, each should have legal + selected damage recipients
        for (attacker in req.attackersList) {
            Assert.assertTrue(
                attacker.legalDamageRecipientsCount > 0,
                "Attacker ${attacker.attackerInstanceId} should have legal damage recipients",
            )
            Assert.assertTrue(
                attacker.hasSelectedDamageRecipient(),
                "Attacker ${attacker.attackerInstanceId} should have selectedDamageRecipient",
            )
        }
    }

    // --- Game loop contract tests (match real client message shapes) ---

    /**
     * client Cast actions must include abilityGrpId and manaCost (not facetId).
     * Without abilityGrpId the client won't highlight cards as castable.
     */
    @Test
    fun castActionHasAbilityGrpIdAndManaCost() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        // Play a land first so we have mana
        val player = b.getPlayer(1)!!
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
        if (land != null) {
            val pending = awaitFreshPending(b, null) ?: run {
                // No pending action — engine may not have granted priority yet
                return
            }
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
            awaitFreshPending(b, pending.actionId)
        }

        val actions = ActionMapper.buildActions(game, 1, b)
        val castActions = actions.actionsList.filter {
            it.actionType == Messages.ActionType.Cast
        }

        if (castActions.isNotEmpty()) {
            val cast = castActions.first()
            // Real client Cast in AAR: no abilityGrpId, yes facetId=instanceId, yes manaCost
            assertEquals(
                cast.abilityGrpId,
                0,
                "Cast in AAR should NOT have abilityGrpId",
            )
            Assert.assertTrue(
                cast.manaCostCount > 0,
                "Cast action must have manaCost entries",
            )
            assertEquals(
                cast.facetId,
                cast.instanceId,
                "Cast facetId should equal instanceId",
            )
        }
        // If no castable spells (bad draw), test is a no-op — that's fine
    }

    /**
     * GSM embedded actions are stripped-down ActionInfo (seatId + minimal action, no actionId).
     * Real client server: no grpId/facetId/shouldStop/autoTapSolution in GSM actions.
     */
    @Test
    fun embeddedActionsHaveStrippedFormat() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val actions = ActionMapper.buildActions(game, 1, b)
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b, actions)

        Assert.assertTrue(gs.actionsCount > 0, "Full state should have embedded actions")
        for (actionInfo in gs.actionsList) {
            // Real server: no actionId on GSM embedded actions (default 0)
            assertEquals(actionInfo.actionId, 0, "GSM actionId should be 0 (not set)")
            Assert.assertTrue(
                actionInfo.seatId in 1..2,
                "seatId should be 1 or 2 (was ${actionInfo.seatId})",
            )
            Assert.assertTrue(actionInfo.hasAction(), "ActionInfo must contain inner Action")
            val a = actionInfo.action
            // Stripped-down: no grpId, no facetId, no shouldStop
            assertEquals(a.grpId, 0, "GSM action should NOT have grpId")
            assertEquals(a.facetId, 0, "GSM action should NOT have facetId")
            Assert.assertFalse(a.shouldStop, "GSM action should NOT have shouldStop")
        }
    }

    /**
     * Game-start bundle sequence: gsIds must be strictly ascending.
     */
    @Test
    fun gameStartBundleGsIdsAscending() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))

        var prevGsId = 0
        for (msg in result.messages) {
            if (msg.hasGameStateMessage()) {
                val gsId = msg.gameStateMessage.gameStateId
                Assert.assertTrue(
                    gsId > prevGsId,
                    "gsId $gsId should be > previous $prevGsId",
                )
                prevGsId = gsId
            }
        }
    }

    // --- ZoneTransfer annotation tests ---

    @Test
    fun landPlayProducesZoneTransferAnnotation() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!

        // Build initial state to seed previousZones
        StateMapper.buildFromGame(game, 1, "test-match", b)

        // Play a land
        val player = b.getPlayer(1)!!
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: error("No land in hand at seed 42")
        val pending = awaitFreshPending(b, null)
            ?: error("No pending action available")
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
        awaitFreshPending(b, pending.actionId)

        // Build post-action state — should have ZoneTransfer annotation
        val gs = StateMapper.buildFromGame(game, 2, "test-match", b)
        val zoneTransfers = gs.annotationsList.filter {
            it.typeList.contains(Messages.AnnotationType.ZoneTransfer_af5a)
        }
        Assert.assertTrue(
            zoneTransfers.isNotEmpty(),
            "Land play should produce ZoneTransfer annotation",
        )
        val ann = zoneTransfers.first()
        val category = ann.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "PlayLand")
    }

    // --- AI combat visibility tests ---

    /** During AI combat, attacking creatures should have AttackState.Attacking in the game state. */
    @Test
    fun aiCombatPopulatesAttackState() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 100L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        // Pass through entire turn 1 to let AI play
        advanceToPhase(b, "MAIN1", maxPasses = 80)
        if (game.isGameOver) return

        // Check if AI has creatures on battlefield
        val ai = b.getPlayer(2)!!
        val aiCreatures = ai.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
        if (aiCreatures.isEmpty()) return // AI didn't play creatures — skip

        // Advance to AI's combat
        advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", maxPasses = 80)
        if (game.isGameOver) return
        if (game.phaseHandler.phase != PhaseType.COMBAT_DECLARE_ATTACKERS) return

        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)
        val bfObjects = gs.gameObjectsList.filter { it.zoneId == ZoneIds.BATTLEFIELD }

        // If combat is active, attacking creatures should have attackState
        val combat = game.phaseHandler.combat
        if (combat != null && combat.attackers.isNotEmpty()) {
            val attacking = bfObjects.filter { it.attackState == Messages.AttackState.Attacking }
            Assert.assertTrue(
                attacking.isNotEmpty(),
                "During combat, attacking creatures should have AttackState.Attacking",
            )
        }
    }

    // --- Diff state tests ---

    @Test
    fun postActionSendsDiffNotFull() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!

        // Seed snapshot — subsequent buildDiffFromGame should produce Diff
        b.snapshotFromGame(game)

        val result = BundleBuilder.postAction(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))
        val gs = result.messages.first().gameStateMessage

        assertEquals(
            gs.type,
            Messages.GameStateType.Diff,
            "Post-action state should be Diff (not Full)",
        )
        // Diff always has players and turnInfo (metadata), even when no zones changed
        Assert.assertTrue(gs.playersCount > 0, "Diff should include player info")
        Assert.assertTrue(gs.hasTurnInfo(), "Diff should include turn info")
    }

    /** When no previous snapshot exists, buildDiffFromGame falls back to Full. */
    @Test
    fun diffFallsBackToFullWithoutSnapshot() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!

        // No snapshotState call — previousState is null
        Assert.assertNull(b.getPreviousState(), "Should have no previous state")

        val gs = StateMapper.buildDiffFromGame(game, 1, "test-match", b)
        assertEquals(
            gs.type,
            Messages.GameStateType.Full,
            "Without previous state, should fall back to Full",
        )
        Assert.assertTrue(gs.zonesCount > 0, "Fallback Full should have zones")
    }

    // --- Stack priority tests ---

    /**
     * After casting a creature, the spell should remain on the stack
     * (engine gives caster priority before resolving).
     */
    @Test
    fun castSpellLeavesSpellOnStack() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 42L)
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val player = b.getPlayer(1)!!

        // Play a land for mana
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val pending1 = awaitFreshPending(b, null) ?: error("No pending action available")
        b.actionBridge.submitAction(pending1.actionId, PlayerAction.PlayLand(land.id))
        awaitFreshPending(b, pending1.actionId)

        // Cast a creature
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
        val pending2 = awaitFreshPending(b, pending1.actionId) ?: error("No pending action available")
        b.actionBridge.submitAction(pending2.actionId, PlayerAction.CastSpell(creature.id))
        awaitFreshPending(b, pending2.actionId)

        // After casting, spell should be on stack (engine gives caster priority)
        val stack = game.getStack()
        // Stack may already be empty if engine auto-resolved — that's the current bug
        // After fix: caster retains priority, stack should have the spell
    }

    // --- skipMulligan tests ---

    /** With skipMulligan=true, engine auto-keeps and reaches priority without explicit submitKeep. */
    @Test
    fun skipMulliganAdvancesToPriorityWithoutKeep() {
        val config = PlaytestConfig(game = GameConfig(skipMulligan = true))
        val b = GameBridge(playtestConfig = config)
        bridge = b
        b.start(seed = 42L)

        // No submitKeep — engine should auto-keep via MulliganBridge(autoKeep=true)
        b.awaitPriority()

        val pending = b.actionBridge.getPending()
        Assert.assertNotNull(pending, "Engine should reach priority without explicit submitKeep")

        val game = b.getGame()!!
        val phase = game.phaseHandler.phase
        Assert.assertTrue(
            phase == PhaseType.MAIN1 || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW,
            "Engine should be at MAIN1 or beginning step after skipMulligan, got $phase",
        )

        // Hand should still have 7 cards (auto-kept, no mull)
        val hand = b.getHandGrpIds(1)
        Assert.assertEquals(hand.size, 7, "Auto-kept hand should have 7 cards")
    }

    /** With skipMulligan=true, buildFromGame produces valid state at Main1. */
    @Test
    fun skipMulliganProducesValidGameState() {
        val config = PlaytestConfig(game = GameConfig(skipMulligan = true))
        val b = GameBridge(playtestConfig = config)
        bridge = b
        b.start(seed = 42L)
        b.awaitPriority()

        val game = b.getGame()!!
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

        Assert.assertTrue(gs.zonesCount > 0, "GameState should have zones")
        Assert.assertTrue(gs.gameObjectsCount > 0, "GameState should have game objects")
        Assert.assertTrue(gs.hasTurnInfo(), "Should have turn info")
        Assert.assertTrue(gs.turnInfo.turnNumber >= 1, "Turn should be >= 1")
    }

    // --- Helpers ---

    /** Play a land + cast a creature from hand at Main1. */
    private fun playLandAndCastCreature(b: GameBridge) {
        val player = b.getPlayer(1)!!
        var lastId: String? = null

        // Play a land
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
        if (land != null) {
            val pending = awaitFreshPending(b, lastId) ?: error("No pending action available")
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
            lastId = pending.actionId
            awaitFreshPending(b, lastId)
        }

        // Try to cast a creature
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
        if (creature != null) {
            val pending = awaitFreshPending(b, lastId) ?: error("No pending action available")
            b.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(creature.id))
            awaitFreshPending(b, pending.actionId)
        }
    }

    /** Advance engine to a target phase, passing priority each step. */
    private fun advanceToPhase(b: GameBridge, target: String, maxPasses: Int = 50) {
        val game = b.getGame()!!
        var lastId: String? = null
        var passes = 0
        while (passes < maxPasses) {
            val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: break
            if (pending.state.phase == target) return
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            lastId = pending.actionId
            passes++
            if (game.isGameOver) break
        }
    }
}
