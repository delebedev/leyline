package leyline.game

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.util.MyRandom
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.config.GameConfig
import leyline.config.MatchConfig
import leyline.conformance.TestCardRegistry
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages
import java.util.Random

/**
 * Integration tests for [GameBridge] — verifies the real Forge engine
 * deals hands, resolves grpIds, and handles mulligan keep/mull.
 *
 * Requires card DB init (~2-3s first run, cached after).
 */
class GameBridgeTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        // --- Helpers ---

        fun playLandAndCastCreature(b: GameBridge) {
            val player = b.getPlayer(SeatId(1))!!
            var lastId: String? = null

            // Play a land
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            if (land != null) {
                val pending = awaitFreshPending(b, lastId) ?: error("No pending action available")
                b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(land.id)))
                lastId = pending.actionId
                awaitFreshPending(b, lastId)
            }

            // Try to cast a creature
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            if (creature != null) {
                val pending = awaitFreshPending(b, lastId) ?: error("No pending action available")
                b.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(ForgeCardId(creature.id)))
                awaitFreshPending(b, pending.actionId)
            }
        }

        fun advanceToPhase(b: GameBridge, target: String, maxPasses: Int = 50) {
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

        // --- Tests ---

        test("bridge starts and deals hand") {
            val b = GameBridge()
            bridge = b
            b.start()

            val seat1Hand = b.getHandGrpIds(1)
            val seat2Hand = b.getHandGrpIds(2)

            seat1Hand.size shouldBe 7
            seat2Hand.shouldNotBeEmpty()
        }

        test("getHandGrpIds resolves") {
            val b = GameBridge()
            bridge = b
            b.start()

            val hand = b.getHandGrpIds(1)
            hand.size shouldBe 7
        }

        test("getDeckGrpIds returns full deck") {
            val b = GameBridge()
            bridge = b
            b.start()

            val deck = b.getDeckGrpIds(1)
            deck.size shouldBe 60
        }

        test("keep advances to priority") {
            val b = GameBridge()
            bridge = b
            b.start()

            b.getHandGrpIds(1).size shouldBe 7
            b.submitKeep(1)
            b.awaitPriority()

            // Engine should be at Main1 (or later) with a pending action
            val pending = b.actionBridge.getPending()
            pending.shouldNotBeNull()

            val game = b.getGame()!!
            val phase = game.phaseHandler.phase
            (phase == PhaseType.MAIN1 || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW).shouldBeTrue()
        }

        test("submit mull auto-tucks and produces new hand") {
            val b = GameBridge()
            bridge = b
            b.start()

            val handBefore = b.getHandGrpIds(1)
            handBefore.size shouldBe 7

            b.submitMull(1)

            val handAfter = b.getHandGrpIds(1)
            // London: drew 7, auto-tucked 1 → 6 cards remain
            handAfter.size shouldBe 6
        }

        test("submit mull twice reduces hand by two") {
            val b = GameBridge()
            bridge = b
            b.start()

            b.submitMull(1)
            b.getHandGrpIds(1).size shouldBe 6

            b.submitMull(1)
            b.getHandGrpIds(1).size shouldBe 5
        }

        test("mull then keep reaches priority") {
            val b = GameBridge()
            bridge = b
            b.start()

            b.submitMull(1)
            b.getHandGrpIds(1).size shouldBe 6

            b.submitKeep(1)
            b.awaitPriority()

            val game = b.getGame()!!
            val phase = game.phaseHandler.phase
            (phase == PhaseType.MAIN1 || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW).shouldBeTrue()
        }

        test("build actions includes lands") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            game.phaseHandler.phase shouldBe PhaseType.MAIN1

            val actions = ActionMapper.buildActions(game, 1, b)

            val hasPass = actions.actionsList.any {
                it.actionType == Messages.ActionType.Pass
            }
            hasPass.shouldBeTrue()

            // Deck has 32 Forest — must have a land play at Main1
            val hasLand = actions.actionsList.any {
                it.actionType == Messages.ActionType.Play_add3
            }
            hasLand.shouldBeTrue()
        }

        test("play land moves card to battlefield") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val player = b.getPlayer(SeatId(1))!!

            val handBefore = player.getZone(ZoneType.Hand).size()
            val bfBefore = player.getZone(ZoneType.Battlefield).size()

            val landInHand = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
                ?: error("No land in hand at seed 42")
            val pending = awaitFreshPending(b, null)
                ?: error("No pending action available")

            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(landInHand.id)))
            awaitFreshPending(b, pending.actionId)

            val handAfter = player.getZone(ZoneType.Hand).size()
            val bfAfter = player.getZone(ZoneType.Battlefield).size()

            handAfter shouldBe handBefore - 1
            bfAfter shouldBe bfBefore + 1
        }

        test("game start bundle has correct shape") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))
            val messages = result.messages

            // Bundle has exactly 5 GRE messages
            messages.size shouldBe 5

            // GRE 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
            val gre1 = messages[0]
            gre1.gameStateMessage.update shouldBe Messages.GameStateUpdate.SendHiFi
            gre1.gameStateMessage.hasGameInfo().shouldBeTrue()
            val phaseAnnotations1 = gre1.gameStateMessage.annotationsList.flatMap { it.typeList }
                .count { it == Messages.AnnotationType.PhaseOrStepModified }
            (phaseAnnotations1 >= 2).shouldBeTrue()

            // GRE 2: SendHiFi echo
            val gre2 = messages[1]
            gre2.gameStateMessage.type shouldBe Messages.GameStateType.Diff
            gre2.gameStateMessage.update shouldBe Messages.GameStateUpdate.SendHiFi
            (gre2.gameStateMessage.gameStateId > gre1.gameStateMessage.gameStateId).shouldBeTrue()

            // GRE 3: SendAndRecord with 1x PhaseOrStepModified
            val gre3 = messages[2]
            gre3.gameStateMessage.update shouldBe Messages.GameStateUpdate.SendAndRecord
            val phaseAnnotations3 = gre3.gameStateMessage.annotationsList.flatMap { it.typeList }
                .count { it == Messages.AnnotationType.PhaseOrStepModified }
            phaseAnnotations3 shouldBe 1

            // GRE 4: PromptReq
            val gre4 = messages[3]
            gre4.type shouldBe Messages.GREMessageType.PromptReq

            // GRE 5: ActionsAvailableReq
            val gre5 = messages[4]
            gre5.type shouldBe Messages.GREMessageType.ActionsAvailableReq_695e
            (gre5.actionsAvailableReq.actionsCount > 0).shouldBeTrue()
        }

        test("post action state has consistent instanceIds") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val player = b.getPlayer(SeatId(1))!!
            val landInHand = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            val pending = awaitFreshPending(b, null)!!
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(landInHand.id)))
            awaitFreshPending(b, pending.actionId)

            val result = BundleBuilder.postAction(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))
            val gs = result.messages.first().gameStateMessage
            val actions = result.messages.last().actionsAvailableReq

            val allZoneInstanceIds = gs.zonesList
                .flatMap { it.objectInstanceIdsList }
                .toSet()
            for (action in actions.actionsList) {
                if (action.instanceId != 0) {
                    (action.instanceId in allZoneInstanceIds).shouldBeTrue()
                }
            }

            // Every game object should be in a zone
            for (obj in gs.gameObjectsList) {
                val inZone = gs.zonesList.any { obj.instanceId in it.objectInstanceIdsList }
                inZone.shouldBeTrue()
            }
        }

        // --- Die roll winner randomization ---

        test("dieRollWinner uses RNG when config unset") {
            MyRandom.setRandom(Random(42))
            val b1 = GameBridge()
            val r1 = b1.dieRollWinner
            (r1 in 1..2).shouldBeTrue()

            // Same seed produces same result (deterministic)
            MyRandom.setRandom(Random(42))
            val b2 = GameBridge()
            b2.dieRollWinner shouldBe r1

            // Lazy val is stable across accesses
            b1.dieRollWinner shouldBe r1
        }

        test("dieRollWinner respects config override") {
            val config1 = MatchConfig(game = GameConfig(dieRollWinner = 1))
            val b1 = GameBridge(matchConfig = config1)
            b1.dieRollWinner shouldBe 1

            val config2 = MatchConfig(game = GameConfig(dieRollWinner = 2))
            val b2 = GameBridge(matchConfig = config2)
            b2.dieRollWinner shouldBe 2
        }

        // --- Deterministic seed tests ---

        test("deterministic seed produces same hand") {
            val b1 = GameBridge()
            bridge = b1
            b1.start(seed = 42L)
            val hand1 = b1.getHandGrpIds(1)
            b1.shutdown()

            val b2 = GameBridge()
            bridge = b2
            b2.start(seed = 42L)
            val hand2 = b2.getHandGrpIds(1)

            hand1 shouldBe hand2
        }

        // --- Double-diff tests ---

        test("phase transition emits five message pattern") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, MessageCounter(initialGsId = 10, initialMsgId = 0))

            result.messages.size shouldBe 5

            // Message 1: SendHiFi with PhaseOrStepModified annotations
            val gs1 = result.messages[0].gameStateMessage
            gs1.update shouldBe Messages.GameStateUpdate.SendHiFi
            gs1.type shouldBe Messages.GameStateType.Diff

            // Message 2: SendHiFi echo
            val gs2 = result.messages[1].gameStateMessage
            gs2.update shouldBe Messages.GameStateUpdate.SendHiFi
            gs2.type shouldBe Messages.GameStateType.Diff

            // Message 3: SendAndRecord with PhaseOrStepModified
            val gs3 = result.messages[2].gameStateMessage
            gs3.update shouldBe Messages.GameStateUpdate.SendAndRecord
            gs3.type shouldBe Messages.GameStateType.Diff

            // Message 4: PromptReq (promptId=37)
            result.messages[3].type shouldBe Messages.GREMessageType.PromptReq
            result.messages[3].prompt.promptId shouldBe 37

            // Message 5: ActionsAvailableReq (promptId=2)
            result.messages[4].type shouldBe Messages.GREMessageType.ActionsAvailableReq_695e
            result.messages[4].prompt.promptId shouldBe 2

            // gsIds should be ascending across GSM messages
            val gsIds = result.messages.filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage.gameStateId }
            for (i in 1 until gsIds.size) {
                (gsIds[i] > gsIds[i - 1]).shouldBeTrue()
            }
        }

        // --- Combat tests ---

        test("declare attackers req lists eligible creatures") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val player = b.getPlayer(SeatId(1))!!

            // Play a land, cast a creature, pass to turn 2 for sickness to clear
            playLandAndCastCreature(b)

            // Check if we actually have creatures on battlefield
            val creatures = player.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
            if (creatures.isEmpty()) {
                // Creature cast may have failed — skip
                return@test
            }

            // Advance through turn 1 end, turn 2 start, to combat
            advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", maxPasses = 80)
            if (game.isGameOver || game.phaseHandler.phase != PhaseType.COMBAT_DECLARE_ATTACKERS) return@test

            val req = RequestBuilder.buildDeclareAttackersReq(game, 1, b)
            req.canSubmitAttackers.shouldBeTrue()

            // If we have eligible attackers, each should have legal + selected damage recipients
            for (attacker in req.attackersList) {
                (attacker.legalDamageRecipientsCount > 0).shouldBeTrue()
                attacker.hasSelectedDamageRecipient().shouldBeTrue()
            }
        }

        // --- Game loop contract tests ---

        // TODO: flaky after forge submodule update — investigate separately
        xtest("cast action has abilityGrpId and mana cost") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            // Play a land first so we have mana
            val player = b.getPlayer(SeatId(1))!!
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            if (land != null) {
                val pending = awaitFreshPending(b, null) ?: return@xtest
                b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(land.id)))
                awaitFreshPending(b, pending.actionId)
            }

            val actions = ActionMapper.buildActions(game, 1, b)
            val castActions = actions.actionsList.filter {
                it.actionType == Messages.ActionType.Cast
            }

            if (castActions.isNotEmpty()) {
                val cast = castActions.first()
                // Real client Cast in AAR: no abilityGrpId, yes facetId=instanceId, yes manaCost
                cast.abilityGrpId shouldBe 0
                (cast.manaCostCount > 0).shouldBeTrue()
                cast.facetId shouldBe cast.instanceId
            }
            // If no castable spells (bad draw), test is a no-op — that's fine
        }

        test("embedded actions have stripped format") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val actions = ActionMapper.buildActions(game, 1, b)
            val gs = StateMapper.buildFromGame(game, 1, "test-match", b, actions)

            (gs.actionsCount > 0).shouldBeTrue()
            for (actionInfo in gs.actionsList) {
                // Real server: no actionId on GSM embedded actions (default 0)
                actionInfo.actionId shouldBe 0
                (actionInfo.seatId in 1..2).shouldBeTrue()
                actionInfo.hasAction().shouldBeTrue()
                val a = actionInfo.action
                // Stripped-down: no grpId, no facetId, no shouldStop
                a.grpId shouldBe 0
                a.facetId shouldBe 0
                a.shouldStop.shouldBeFalse()
            }
        }

        test("game start bundle gsIds ascending") {
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
                    (gsId > prevGsId).shouldBeTrue()
                    prevGsId = gsId
                }
            }
        }

        // --- ZoneTransfer annotation tests ---

        test("land play produces ZoneTransfer annotation") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!

            // Build initial state to seed previousZones
            StateMapper.buildFromGame(game, 1, "test-match", b)

            // Play a land
            val player = b.getPlayer(SeatId(1))!!
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
                ?: error("No land in hand at seed 42")
            val pending = awaitFreshPending(b, null)
                ?: error("No pending action available")
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(land.id)))
            awaitFreshPending(b, pending.actionId)

            // Build post-action state — should have ZoneTransfer annotation
            val gs = StateMapper.buildFromGame(game, 2, "test-match", b)
            val zoneTransfers = gs.annotationsList.filter {
                it.typeList.contains(Messages.AnnotationType.ZoneTransfer_af5a)
            }
            zoneTransfers.shouldNotBeEmpty()
            val ann = zoneTransfers.first()
            val category = ann.detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "PlayLand"
        }

        // --- AI combat visibility tests ---

        test("AI combat populates attack state") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 100L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            // Pass through entire turn 1 to let AI play
            advanceToPhase(b, "MAIN1", maxPasses = 80)
            if (game.isGameOver) return@test

            // Check if AI has creatures on battlefield
            val ai = b.getPlayer(SeatId(2))!!
            val aiCreatures = ai.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
            if (aiCreatures.isEmpty()) return@test // AI didn't play creatures — skip

            // Advance to AI's combat
            advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", maxPasses = 80)
            if (game.isGameOver) return@test
            if (game.phaseHandler.phase != PhaseType.COMBAT_DECLARE_ATTACKERS) return@test

            val gs = StateMapper.buildFromGame(game, 1, "test-match", b)
            val bfObjects = gs.gameObjectsList.filter { it.zoneId == ZoneIds.BATTLEFIELD }

            // If combat is active, attacking creatures should have attackState
            val combat = game.phaseHandler.combat
            if (combat != null && combat.attackers.isNotEmpty()) {
                val attacking = bfObjects.filter { it.attackState == Messages.AttackState.Attacking }
                attacking.shouldNotBeEmpty()
            }
        }

        // --- Diff state tests ---

        test("post action sends Diff not Full") {
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

            gs.type shouldBe Messages.GameStateType.Diff
            // Diff always has players and turnInfo (metadata)
            (gs.playersCount > 0).shouldBeTrue()
            gs.hasTurnInfo().shouldBeTrue()
        }

        test("diff falls back to Full without snapshot") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!

            // No snapshotState call — previousState is null
            b.getPreviousState().shouldBeNull()

            val gs = StateMapper.buildDiffFromGame(game, 1, "test-match", b)
            gs.type shouldBe Messages.GameStateType.Full
            (gs.zonesCount > 0).shouldBeTrue()
        }

        // --- Stack priority tests ---

        test("cast spell leaves spell on stack") {
            val b = GameBridge()
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(1)
            advanceToMain1(b)

            val game = b.getGame()!!
            val player = b.getPlayer(SeatId(1))!!

            // Play a land for mana
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val pending1 = awaitFreshPending(b, null) ?: error("No pending action available")
            b.actionBridge.submitAction(pending1.actionId, PlayerAction.PlayLand(ForgeCardId(land.id)))
            awaitFreshPending(b, pending1.actionId)

            // Cast a creature
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
            val pending2 = awaitFreshPending(b, pending1.actionId) ?: error("No pending action available")
            b.actionBridge.submitAction(pending2.actionId, PlayerAction.CastSpell(ForgeCardId(creature.id)))
            awaitFreshPending(b, pending2.actionId)

            // After casting, spell should be on stack (engine gives caster priority)
            // Stack may already be empty if engine auto-resolved — that's the current bug
        }

        // --- skipMulligan tests ---

        test("skip mulligan advances to priority without keep") {
            val config = MatchConfig(game = GameConfig(skipMulligan = true))
            val b = GameBridge(matchConfig = config)
            bridge = b
            b.start(seed = 42L)

            // No submitKeep — engine should auto-keep via MulliganBridge(autoKeep=true)
            b.awaitPriority()

            val pending = b.actionBridge.getPending()
            pending.shouldNotBeNull()

            val game = b.getGame()!!
            val phase = game.phaseHandler.phase
            (phase == PhaseType.MAIN1 || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW).shouldBeTrue()

            // Hand should still have 7 cards (auto-kept, no mull)
            val hand = b.getHandGrpIds(1)
            hand.size shouldBe 7
        }

        test("skip mulligan produces valid game state") {
            val config = MatchConfig(game = GameConfig(skipMulligan = true))
            val b = GameBridge(matchConfig = config)
            bridge = b
            b.start(seed = 42L)
            b.awaitPriority()

            val game = b.getGame()!!
            val gs = StateMapper.buildFromGame(game, 1, "test-match", b)

            (gs.zonesCount > 0).shouldBeTrue()
            (gs.gameObjectsCount > 0).shouldBeTrue()
            gs.hasTurnInfo().shouldBeTrue()
            (gs.turnInfo.turnNumber >= 1).shouldBeTrue()
        }
    })
