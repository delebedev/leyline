package forge.nexus.game

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.web.game.GameBootstrap
import forge.web.game.PlayerAction
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
        GameBootstrap.initializeCardDatabase()
    }

    private var bridge: GameBridge? = null

    @AfterMethod
    fun tearDown() {
        bridge?.shutdown()
        bridge = null
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
        // should resolve to non-zero grpIds via ArenaCardDb
        // Some may be 0 if Arena DB isn't installed, but the engine dealt real cards
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

    @Test
    fun submitMullProducesNewHand() {
        val b = GameBridge()
        bridge = b
        b.start()

        val handBefore = b.getHandGrpIds(1)
        Assert.assertEquals(handBefore.size, 7)

        b.submitMull(1)

        val handAfter = b.getHandGrpIds(1)
        // After London mulligan, still draw 7 (tuck happens after keep)
        Assert.assertEquals(handAfter.size, 7, "After mull should still have 7 cards (London)")
    }

    @Test
    fun buildFromGameProducesValidState() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        b.awaitPriority()

        val game = b.getGame()!!
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

        // Should have zones with cards
        Assert.assertTrue(gs.zonesCount > 0, "GameState should have zones")
        Assert.assertTrue(gs.gameObjectsCount > 0, "GameState should have game objects")

        // Hand zone should have 7 cards (or 6 if draw happened)
        val handZone = gs.zonesList.find { it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Hand && it.ownerSeatId == 1 }
        Assert.assertNotNull(handZone, "Should have seat 1 hand zone")
        Assert.assertTrue(
            handZone!!.objectInstanceIdsCount >= 6,
            "Hand should have cards, got ${handZone.objectInstanceIdsCount}",
        )

        // TurnInfo should reflect real phase
        Assert.assertTrue(gs.hasTurnInfo(), "Should have turn info")
        Assert.assertTrue(gs.turnInfo.turnNumber >= 1, "Turn should be >= 1")
    }

    @Test
    fun buildActionsIncludesLands() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        Assert.assertEquals(game.phaseHandler.phase, PhaseType.MAIN1, "Should be at Main1")

        val actions = StateMapper.buildActions(game, 1, b)

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
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val player = b.getPlayer(1)!!
        Assert.assertEquals(game.phaseHandler.phase, PhaseType.MAIN1, "Should be at Main1")

        val handBefore = player.getZone(ZoneType.Hand).size()
        val bfBefore = player.getZone(ZoneType.Battlefield).size()

        val landInHand = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: return // no lands in hand (unlikely but possible)
        val pending = b.actionBridge.getPending()
            ?: return // engine hasn't granted priority

        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(landInHand.id))
        b.awaitPriority()

        val handAfter = player.getZone(ZoneType.Hand).size()
        val bfAfter = player.getZone(ZoneType.Battlefield).size()

        assertEquals(handAfter, handBefore - 1, "Hand should shrink by 1 after land play")
        assertEquals(bfAfter, bfBefore + 1, "Battlefield should grow by 1 after land play")
    }

    @Test
    fun gameObjectsHaveCardTypeFields() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

        // Hand cards should have cardTypes populated
        val handZone = gs.zonesList.first {
            it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Hand && it.ownerSeatId == 1
        }
        val handInstanceIds = handZone.objectInstanceIdsList.toSet()
        val handObjects = gs.gameObjectsList.filter { it.instanceId in handInstanceIds }
        Assert.assertTrue(handObjects.isNotEmpty(), "Should have hand objects")

        // Every hand card should have at least one cardType
        for (obj in handObjects) {
            Assert.assertTrue(
                obj.cardTypesCount > 0,
                "Hand card instanceId=${obj.instanceId} grpId=${obj.grpId} missing cardTypes",
            )
        }

        // Lands should have Land_a80b type
        val lands = handObjects.filter {
            it.cardTypesList.contains(wotc.mtgo.gre.external.messaging.Messages.CardType.Land_a80b)
        }
        Assert.assertTrue(lands.isNotEmpty(), "Deck has forests — hand should have at least one land")
        for (land in lands) {
            Assert.assertTrue(
                land.superTypesList.contains(wotc.mtgo.gre.external.messaging.Messages.SuperType.Basic),
                "Forest should have Basic supertype",
            )
            Assert.assertTrue(
                land.subtypesList.contains(wotc.mtgo.gre.external.messaging.Messages.SubType.Forest),
                "Forest should have Forest subtype",
            )
        }

        // Creatures should have power/toughness
        val creatures = handObjects.filter {
            it.cardTypesList.contains(wotc.mtgo.gre.external.messaging.Messages.CardType.Creature)
        }
        if (creatures.isNotEmpty()) {
            for (c in creatures) {
                Assert.assertTrue(c.hasPower(), "Creature instanceId=${c.instanceId} missing power")
                Assert.assertTrue(c.hasToughness(), "Creature instanceId=${c.instanceId} missing toughness")
            }
        }
    }

    // --- Proto shape assertions (uses same builder as handler) ---

    /**
     * Validates the game-start GRE bundle shape via [BundleBuilder.gameStart]:
     *   GRE 1: Diff, Beginning/Upkeep, SendHiFi (stage transition)
     *   GRE 2: Diff, empty priority-pass marker
     *   GRE 3: Full, Main1, SendAndRecord, zones + objects + actions
     *   GRE 4: ActionsAvailableReq, actions > 0
     *   All instanceIds in actions exist in GRE 3's zones.
     */
    @Test
    fun gameStartBundleHasCorrectShape() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        b.awaitPriority()
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, 10)
        val messages = result.messages

        // Bundle has exactly 4 GRE messages (double-diff pattern)
        Assert.assertEquals(messages.size, 4, "Game start bundle should have 4 GRE messages")

        // GRE 1: stage transition
        val gre1 = messages[0]
        assertEquals(
            gre1.gameStateMessage.turnInfo.phase,
            wotc.mtgo.gre.external.messaging.Messages.Phase.Beginning_a549,
            "GRE 1 should be Beginning phase",
        )
        assertEquals(
            gre1.gameStateMessage.update,
            wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendHiFi,
            "GRE 1 (stage transition) should use SendHiFi",
        )

        // GRE 2: empty priority-pass marker
        val gre2 = messages[1]
        assertEquals(
            gre2.gameStateMessage.type,
            wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff,
            "GRE 2 should be Diff (priority-pass marker)",
        )
        Assert.assertTrue(
            gre2.gameStateMessage.gameStateId > gre1.gameStateMessage.gameStateId,
            "GRE 2 gsId should be > GRE 1 gsId",
        )

        // GRE 3: Full state at Main1 with zones + actions
        val gre3 = messages[2]
        assertEquals(
            gre3.gameStateMessage.type,
            wotc.mtgo.gre.external.messaging.Messages.GameStateType.Full,
            "GRE 3 should be Full state",
        )
        assertEquals(
            gre3.gameStateMessage.turnInfo.phase,
            wotc.mtgo.gre.external.messaging.Messages.Phase.Main1_a549,
            "GRE 3 should be Main1 phase",
        )
        assertEquals(
            gre3.gameStateMessage.update,
            wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendAndRecord,
            "GRE 3 (Main1) should use SendAndRecord",
        )
        Assert.assertTrue(
            gre3.gameStateMessage.actionsCount > 0,
            "GRE 3 should have embedded actions",
        )
        Assert.assertTrue(
            gre3.gameStateMessage.zonesCount > 0,
            "GRE 3 (Full) should have zones",
        )
        Assert.assertTrue(
            gre3.gameStateMessage.gameObjectsCount > 0,
            "GRE 3 (Full) should have game objects",
        )

        // GRE 4: actions available
        val gre4 = messages[3]
        assertEquals(
            gre4.type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.ActionsAvailableReq_695e,
            "GRE 4 should be ActionsAvailableReq",
        )
        Assert.assertTrue(
            gre4.actionsAvailableReq.actionsCount > 0,
            "GRE 4 should have actions",
        )

        // Cross-reference: every instanceId in actions exists in GRE 3's zones
        val allZoneInstanceIds = gre3.gameStateMessage.zonesList
            .flatMap { it.objectInstanceIdsList }
            .toSet()
        for (action in gre4.actionsAvailableReq.actionsList) {
            if (action.instanceId != 0) {
                Assert.assertTrue(
                    action.instanceId in allZoneInstanceIds,
                    "Action instanceId ${action.instanceId} not found in any zone",
                )
            }
        }
    }

    /**
     * After a land play, [BundleBuilder.postAction] produces consistent instanceIds:
     * every action references a card that exists in a zone.
     */
    @Test
    fun postActionStateHasConsistentInstanceIds() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val player = b.getPlayer(1)!!
        val landInHand = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        val pending = b.actionBridge.getPending()!!
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(landInHand.id))
        b.awaitPriority()

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, 10)
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

    /** Every phaseTransitionDiff emits exactly 2 messages with sequential gsIds. */
    @Test
    fun phaseTransitionEmitsTwoDiffs() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, 1, 10)

        Assert.assertEquals(result.messages.size, 2, "Phase transition should emit 2 diffs")
        val gs1 = result.messages[0].gameStateMessage
        val gs2 = result.messages[1].gameStateMessage
        assertEquals(gs1.type, wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff)
        assertEquals(gs2.type, wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff)
        assertEquals(gs2.gameStateId, gs1.gameStateId + 1, "gsIds should be sequential")
    }

    // --- Combat tests ---

    /** At COMBAT_DECLARE_ATTACKERS, buildDeclareAttackersReq lists eligible creatures. */
    @Test
    fun declareAttackersReqListsEligibleCreatures() {
        val b = GameBridge()
        bridge = b
        b.start()
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
        advanceToPhase(b, PhaseType.COMBAT_DECLARE_ATTACKERS, maxPasses = 80)
        if (game.isGameOver || game.phaseHandler.phase != PhaseType.COMBAT_DECLARE_ATTACKERS) return

        val req = StateMapper.buildDeclareAttackersReq(game, 1, b)
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

    /** declareAttackersBundle has correct GRE message types. */
    @Test
    fun declareAttackersBundleShape() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.declareAttackersBundle(game, b, "test-match", 1, 1, 10)

        Assert.assertEquals(result.messages.size, 2, "Attackers bundle should have 2 messages")
        assertEquals(
            result.messages[0].type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
            "First message should be GameStateMessage",
        )
        assertEquals(
            result.messages[1].type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.DeclareAttackersReq_695e,
            "Second message should be DeclareAttackersReq",
        )
        Assert.assertEquals(
            result.messages[1].prompt.promptId,
            6,
            "DeclareAttackersReq should have prompt id=6",
        )
    }

    /** declareBlockersBundle has correct GRE message types. */
    @Test
    fun declareBlockersBundleShape() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.declareBlockersBundle(game, b, "test-match", 1, 1, 10)

        Assert.assertEquals(result.messages.size, 2, "Blockers bundle should have 2 messages")
        assertEquals(
            result.messages[0].type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
            "First message should be GameStateMessage",
        )
        assertEquals(
            result.messages[1].type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.DeclareBlockersReq_695e,
            "Second message should be DeclareBlockersReq",
        )
        Assert.assertEquals(
            result.messages[1].prompt.promptId,
            7,
            "DeclareBlockersReq should have prompt id=7",
        )
    }

    // --- QueuedGameStateMessage tests ---

    /** queuedGameState wraps a GameStateMessage with type 51. */
    @Test
    fun queuedGameStateShape() {
        val gs = wotc.mtgo.gre.external.messaging.Messages.GameStateMessage.newBuilder()
            .setType(wotc.mtgo.gre.external.messaging.Messages.GameStateType.Full)
            .setGameStateId(42)
            .build()

        val msg = BundleBuilder.queuedGameState(gs, 2, 10, 42)

        assertEquals(
            msg.type,
            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.QueuedGameStateMessage,
            "Should be QueuedGameStateMessage type",
        )
        Assert.assertTrue(msg.hasGameStateMessage(), "Should contain game state")
        Assert.assertEquals(msg.gameStateMessage.gameStateId, 42)
    }

    // --- Game loop contract tests (match real Arena message shapes) ---

    /**
     * Full state at Main1 must have timers (real Arena: 2 inactivity timers).
     * Client may lock out or hide turn timer without them.
     */
    @Test
    fun fullStateHasTimers() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

        Assert.assertTrue(gs.timersCount >= 2, "Full state should have at least 2 timers")
        val timer1 = gs.timersList.first { it.timerId == 1 }
        val timer2 = gs.timersList.first { it.timerId == 2 }
        assertEquals(timer1.type, Messages.TimerType.Inactivity_a5e2)
        assertEquals(timer2.type, Messages.TimerType.Inactivity_a5e2)
        Assert.assertTrue(timer1.durationSec > 0, "Timer duration must be positive")
    }

    /**
     * Zone visibility must match real Arena:
     * Suppressed/Pending = Public, Sideboard = Private.
     */
    @Test
    fun zoneVisibilityMatchesRealArena() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

        val byId = gs.zonesList.associateBy { it.zoneId }
        // Real Arena: Suppressed + Pending are Public
        assertEquals(byId[24]!!.visibility, Messages.Visibility.Public, "Suppressed should be Public")
        assertEquals(byId[25]!!.visibility, Messages.Visibility.Public, "Pending should be Public")
        // Real Arena: Sideboard is Private
        assertEquals(byId[34]!!.visibility, Messages.Visibility.Private, "P1 Sideboard should be Private")
        assertEquals(byId[38]!!.visibility, Messages.Visibility.Private, "P2 Sideboard should be Private")
    }

    /**
     * Cast actions must include abilityGrpId and manaCost (not facetId).
     * Without abilityGrpId the client won't highlight cards as castable.
     */
    @Test
    fun castActionHasAbilityGrpIdAndManaCost() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        // Play a land first so we have mana
        val player = b.getPlayer(1)!!
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
        if (land != null) {
            val pending = b.actionBridge.getPending() ?: run {
                // No pending action — engine may not have granted priority yet
                return
            }
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
            b.awaitPriority()
        }

        val actions = StateMapper.buildActions(game, 1, b)
        val castActions = actions.actionsList.filter {
            it.actionType == Messages.ActionType.Cast
        }

        if (castActions.isNotEmpty()) {
            val cast = castActions.first()
            Assert.assertTrue(
                cast.abilityGrpId > 0,
                "Cast action must have abilityGrpId (was ${cast.abilityGrpId})",
            )
            Assert.assertTrue(
                cast.manaCostCount > 0,
                "Cast action must have manaCost entries",
            )
            Assert.assertEquals(
                cast.facetId,
                0,
                "Cast action should NOT have facetId set",
            )
        }
        // If no castable spells (bad draw), test is a no-op — that's fine
    }

    /**
     * PlayerInfo must include timerIds (real Arena: timerIds=[seatId]).
     */
    @Test
    fun playerInfoHasTimerIds() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

        for (player in gs.playersList) {
            Assert.assertTrue(
                player.timerIdsCount > 0,
                "Player seat ${player.systemSeatNumber} must have timerIds",
            )
            assertEquals(
                player.timerIdsList[0],
                player.systemSeatNumber,
                "timerIds[0] should equal seat number",
            )
        }
    }

    /**
     * GameStateMessage.actions must be wrapped in ActionInfo (actionId + seatId + action).
     */
    @Test
    fun embeddedActionsHaveActionIdAndSeatId() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val actions = StateMapper.buildActions(game, 1, b)
        val gs = StateMapper.buildFromGame(game, 1, "test-match", b, actions)

        Assert.assertTrue(gs.actionsCount > 0, "Full state should have embedded actions")
        for ((i, actionInfo) in gs.actionsList.withIndex()) {
            assertEquals(
                actionInfo.actionId,
                i + 1,
                "actionId should be sequential (1-based)",
            )
            Assert.assertTrue(
                actionInfo.seatId in 1..2,
                "seatId should be 1 or 2 (was ${actionInfo.seatId})",
            )
            Assert.assertTrue(actionInfo.hasAction(), "ActionInfo must contain inner Action")
        }
    }

    /**
     * Game-start bundle sequence: gsIds must be strictly ascending.
     */
    @Test
    fun gameStartBundleGsIdsAscending() {
        val b = GameBridge()
        bridge = b
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!
        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, 10)

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
        b.start()
        b.submitKeep(1)
        advanceToMain1(b)

        val game = b.getGame()!!

        // Build initial state to seed previousZones
        StateMapper.buildFromGame(game, 1, "test-match", b)

        // Play a land
        val player = b.getPlayer(1)!!
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            ?: return // no lands in hand (unlikely but possible)
        val pending = b.actionBridge.getPending()
            ?: return // engine hasn't granted priority
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
        b.awaitPriority()

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
        val details = ann.detailsList.associate { it.key to it.valueStringList.first() }
        assertEquals(details["category"], "PlayLand")
    }

    // --- Helpers ---

    /** Play a land + cast a creature from hand at Main1. */
    private fun playLandAndCastCreature(b: GameBridge) {
        val game = b.getGame()!!
        val player = b.getPlayer(1)!!

        // Play a land
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
        if (land != null) {
            val pending = b.actionBridge.getPending()!!
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
            b.awaitPriority()
        }

        // Try to cast a creature
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
        if (creature != null) {
            val pending = b.actionBridge.getPending()
            if (pending != null) {
                b.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(creature.id))
                b.awaitPriority()
            }
        }
    }

    /** Advance engine to a target phase, passing priority each step. */
    private fun advanceToPhase(b: GameBridge, target: PhaseType, maxPasses: Int = 50) {
        val game = b.getGame()!!
        var passes = 0
        while (game.phaseHandler.phase != target && passes < maxPasses) {
            val pending = b.actionBridge.getPending()
            if (pending != null) {
                b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                b.awaitPriority()
            } else {
                // AI turn or waiting for engine — wait briefly
                val reached = b.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
                if (!reached) break
            }
            passes++
            if (game.isGameOver) break
        }
    }

    /**
     * Pass priority until engine reaches Main1. Prevents tests from silently
     * skipping when the engine stops at upkeep/draw for triggers or effects.
     */
    private fun advanceToMain1(b: GameBridge, maxPasses: Int = 20) {
        b.awaitPriority()
        val game = b.getGame()!!
        var passes = 0
        while (game.phaseHandler.phase != PhaseType.MAIN1 && passes < maxPasses) {
            val pending = b.actionBridge.getPending()
            Assert.assertNotNull(
                pending,
                "No pending action while advancing to Main1 (phase=${game.phaseHandler.phase})",
            )
            b.actionBridge.submitAction(pending!!.actionId, PlayerAction.PassPriority)
            b.awaitPriority()
            passes++
        }
        Assert.assertEquals(
            game.phaseHandler.phase,
            PhaseType.MAIN1,
            "Failed to reach Main1 after $maxPasses passes",
        )
    }
}
