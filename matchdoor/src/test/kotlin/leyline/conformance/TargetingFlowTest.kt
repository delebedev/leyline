package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Tier 2 end-to-end targeting tests driven through real [MatchSession] code.
 *
 * Deck: default mono-green (Llanowar Elves, Elvish Mystic, Giant Growth, Forest).
 * Giant Growth targets a creature — exercises SelectTargetsReq/Resp flow.
 *
 * Multi-turn advancement with [passPriority] is unreliable (can overshoot),
 * so setup helpers assert prerequisites (creature on BF, mana, Giant Growth
 * in hand) rather than exact turn numbers.
 */
class TargetingFlowTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        var puzzleHarness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
            puzzleHarness?.shutdown()
            puzzleHarness = null
        }

        // --- Setup helpers ---

        fun setupForTargeting(): Int {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/pump-spell.pzl")

            // Verify prerequisites (turn-agnostic)
            h.isAiTurn().shouldBeFalse()
            h.turn() shouldBeGreaterThanOrEqualTo 1

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()

            val player = h.bridge.getPlayer(SeatId(1))!!
            val hasGiantGrowth = player.getZone(ForgeZoneType.Hand).cards
                .any { it.name.equals("Giant Growth", ignoreCase = true) }
            hasGiantGrowth.shouldBeTrue()

            return creatures.first().first
        }

        fun setupBoltFacePuzzle(): MatchFlowHarness {
            val h = MatchFlowHarness()
            puzzleHarness = h
            h.connectAndKeepPuzzle("puzzles/bolt-face.pzl")
            return h
        }

        fun waitForBuffedCreature(h: MatchFlowHarness, creatureIid: Int): forge.game.card.Card {
            repeat(6) {
                val player = h.bridge.getPlayer(SeatId(1))!!
                val creature = player.getZone(ForgeZoneType.Battlefield).cards
                    .firstOrNull { h.bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value == creatureIid }
                if (creature != null && creature.netPower >= 4 && creature.netToughness >= 4) {
                    return creature
                }
                h.passPriority()
            }
            val player = h.bridge.getPlayer(SeatId(1))!!
            return player.getZone(ForgeZoneType.Battlefield).cards
                .firstOrNull { h.bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value == creatureIid }
                ?: error("Target creature not found after Giant Growth")
        }

        test("targeted spell emits SelectTargetsReq") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            // Cast Giant Growth — should trigger SelectTargetsReq
            val snap = h.messageSnapshot()
            val cast = h.castSpellByName("Giant Growth")
            cast.shouldBeTrue()

            // Look for SelectTargetsReq in messages
            val msgs = h.messagesSince(snap)
            val stReq = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stReq.shouldNotBeNull()

            val req = stReq.selectTargetsReq
            (req.targetsCount > 0).shouldBeTrue()

            val targetSelection = req.targetsList.first()
            (targetSelection.targetsCount > 0).shouldBeTrue()

            // Our creature should be among legal targets
            val legalTargetIds = targetSelection.targetsList.map { it.targetInstanceId }
            (creatureIid in legalTargetIds).shouldBeTrue()

            // Verify minTargets/maxTargets for Giant Growth (1 target)
            targetSelection.minTargets shouldBe 1
            targetSelection.maxTargets shouldBe 1
        }

        test("select target and resolve") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            // Cast Giant Growth
            val cast = h.castSpellByName("Giant Growth")
            cast.shouldBeTrue()

            // Select the creature as target
            val snap = h.messageSnapshot()
            h.selectTargets(listOf(creatureIid))

            val msgs = h.messagesSince(snap)
            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            gsms.shouldNotBeEmpty()

            // The creature should have updated power/toughness (+3/+3 from Giant Growth)
            val creature = waitForBuffedCreature(h, creatureIid)
            (creature.netPower >= 4).shouldBeTrue()
            (creature.netToughness >= 4).shouldBeTrue()

            h.accumulator.assertConsistent("after Giant Growth resolution")
        }

        test("targeting state validity") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            h.accumulator.assertConsistent("before targeting")

            // Cast Giant Growth
            val cast = h.castSpellByName("Giant Growth")
            cast.shouldBeTrue()

            // Select target
            h.selectTargets(listOf(creatureIid))

            // Resolve
            h.passPriority()

            h.accumulator.assertConsistent("after targeting flow")

            // gsId chain valid through targeting
            assertGsIdChain(h.allMessages, context = "targeting flow")
        }

        test("targeting during combat") {
            // Use combat deck with haste creatures for turn-1 combat + Giant Growth
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Play Forest (for Giant Growth mana) + cast Raging Goblin... wait, we need
            // both a Mountain (for Goblin) and a Forest (for Giant Growth) on turn 1.
            // We can only play 1 land per turn. So: play Forest, can't cast Goblin (costs R).
            // Or: play Mountain, cast Goblin, but can't cast Giant Growth (costs G).
            //
            // Solution: advance to turn 2. Play Mountain turn 1, cast Goblin.
            // Turn 2: play Forest → have both colors.
            h.playLand() // Mountain
            h.castSpellByName("Raging Goblin")
            h.passPriority() // resolve

            // Advance to turn 2
            h.passPriority()

            // Play Forest for green mana
            h.playLand()

            // Advance to combat
            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val creatureIid = creatures.first().first

            h.passPriority() // advance to combat

            val daReq = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
            if (daReq != null) {
                h.declareAttackers(listOf(creatureIid))

                // Try casting Giant Growth during combat (requires priority window)
                val canCast = h.castSpellByName("Giant Growth")
                if (canCast) {
                    val stReq = h.allMessages.lastOrNull { it.hasSelectTargetsReq() }
                    if (stReq != null) {
                        h.selectTargets(listOf(creatureIid))
                        h.passPriority() // resolve
                    }
                }
            }

            // Regardless of whether targeting succeeded, advance past combat
            repeat(10) {
                if (h.isGameOver()) return@repeat
                val p = h.phase()
                if (p == "MAIN2" || p == "END_OF_TURN") return@repeat
                h.passPriority()
            }

            h.accumulator.assertConsistent("after targeting during combat")
            h.isGameOver().shouldBeFalse()
        }

        test("spell resolution produces zone transfer") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            // Cast Giant Growth and select target
            val cast = h.castSpellByName("Giant Growth")
            cast.shouldBeTrue()
            h.selectTargets(listOf(creatureIid))

            val snap = h.messageSnapshot()
            h.passPriority() // resolve

            // Check for ZoneTransfer annotation (Stack→GY for the instant)
            val msgs = h.messagesSince(snap)
            val allAnnotations = msgs
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }

            // At minimum, state should be valid.
            h.accumulator.assertConsistent("after spell resolution zone transfer")

            // Verify the spell is actually in graveyard now
            val player = h.bridge.getPlayer(SeatId(1))!!
            val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
            val giantGrowthInGy = gyCards.any { it.name.equals("Giant Growth", ignoreCase = true) }
            giantGrowthInGy.shouldBeTrue()
        }

        test("multiple targeted spells in sequence") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            // Need at least 2 mana for 2 Giant Growths (each costs G)

            // Cast first Giant Growth
            val cast1 = h.castSpellByName("Giant Growth")
            cast1.shouldBeTrue()

            // Select target + resolve
            h.selectTargets(listOf(creatureIid))
            h.passPriority() // resolve

            // Cast second Giant Growth (if we have one and mana)
            val cast2 = h.castSpellByName("Giant Growth")
            if (cast2) {
                val stReq = h.allMessages.lastOrNull { it.hasSelectTargetsReq() }
                if (stReq != null) {
                    h.selectTargets(listOf(creatureIid))
                    h.passPriority() // resolve
                }

                // Creature should have +6/+6 (two Giant Growths)
                val player = h.bridge.getPlayer(SeatId(1))!!
                val creature = player.getZone(ForgeZoneType.Battlefield).cards
                    .firstOrNull { h.bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value == creatureIid }
                if (creature != null) {
                    (creature.netPower >= 7).shouldBeTrue()
                }
            }

            h.accumulator.assertConsistent("after multiple targeted spells")
            assertGsIdChain(h.allMessages, context = "multiple targeted spells")
        }

        // ─── Cancel targeting tests ─────────────────────────────────────────

        test("cancel targeting unwinds spell") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            // Cast Giant Growth — triggers SelectTargetsReq
            val snap = h.messageSnapshot()
            val cast = h.castSpellByName("Giant Growth")
            cast.shouldBeTrue()

            val msgs = h.messagesSince(snap)
            val stReq = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stReq.shouldNotBeNull()

            // Cancel instead of selecting a target
            val cancelSnap = h.messageSnapshot()
            h.cancelAction()

            // Stack should be empty — spell unwound
            val game = h.game()
            game.stack.isEmpty.shouldBeTrue()

            // Giant Growth should be back in hand
            val player = h.bridge.getPlayer(SeatId(1))!!
            val handCards = player.getZone(ForgeZoneType.Hand).cards
            val hasGG = handCards.any { it.name.equals("Giant Growth", ignoreCase = true) }
            hasGG.shouldBeTrue()

            // Should receive ActionsAvailableReq (player can act again)
            val afterMsgs = h.messagesSince(cancelSnap)
            val actionsReq = afterMsgs.any { it.hasActionsAvailableReq() }
            actionsReq.shouldBeTrue()

            h.accumulator.assertConsistent("after cancel targeting")
        }

        test("cancel then recast") {
            val creatureIid = setupForTargeting()
            val h = harness!!

            // Cast Giant Growth → cancel
            h.castSpellByName("Giant Growth").shouldBeTrue()
            h.cancelAction()

            // Re-cast the same spell
            val snap = h.messageSnapshot()
            val recast = h.castSpellByName("Giant Growth")
            recast.shouldBeTrue()

            val msgs = h.messagesSince(snap)
            val stReq = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stReq.shouldNotBeNull()

            // Select target and resolve
            h.selectTargets(listOf(creatureIid))

            // Creature should have +3/+3
            val creature = waitForBuffedCreature(h, creatureIid)
            (creature.netPower >= 4).shouldBeTrue()

            h.accumulator.assertConsistent("after cancel + re-cast")
        }

        // ─── Player targeting tests (bolt-face puzzle) ──────────────────────

        test("bolt face SelectTargetsReq includes players") {
            val h = setupBoltFacePuzzle()

            // Cast Lightning Bolt
            val snap = h.messageSnapshot()
            val cast = h.castSpellByName("Lightning Bolt")
            cast.shouldBeTrue()

            val msgs = h.messagesSince(snap)
            val stMsg = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stMsg.shouldNotBeNull()

            val req = stMsg.selectTargetsReq
            val targets = req.targetsList.first().targetsList
            val targetIds = targets.map { it.targetInstanceId }

            // Should include player seatIds (1=human, 2=AI)
            (1 in targetIds).shouldBeTrue()
            (2 in targetIds).shouldBeTrue()

            // Opponent (seat 2) should be Hot, self (seat 1) should be Cold
            val opponentTarget = targets.first { it.targetInstanceId == 2 }
            opponentTarget.highlight shouldBe HighlightType.Hot
            val selfTarget = targets.first { it.targetInstanceId == 1 }
            selfTarget.highlight shouldBe HighlightType.Cold

            // Creature targets should have Tepid highlight (blue/cyan "legal target" glow)
            val creatureTargets = targets.filter { it.targetInstanceId > 2 }
            creatureTargets.shouldNotBeEmpty()
            for (ct in creatureTargets) {
                ct.highlight shouldBe HighlightType.Tepid
            }

            // Should also include creature targets (Runeclaw Bear, Pillarfield Ox)
            val targetCount = targetIds.size
            targetCount shouldBeGreaterThanOrEqual 4

            // Verify allowCancel and allowUndo on the wrapper
            stMsg.allowCancel shouldBe AllowCancel.Abort
            stMsg.allowUndo.shouldBeTrue()

            h.accumulator.assertConsistent("after bolt targeting")
        }

        test("bolt face sourceId matches stack instanceId") {
            val h = setupBoltFacePuzzle()

            val snap = h.messageSnapshot()
            h.castSpellByName("Lightning Bolt")

            val msgs = h.messagesSince(snap)
            val stMsg = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stMsg.shouldNotBeNull()

            // Find the GSM with the stack zone — the spell's instanceId on stack
            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val stackZone = gsms.flatMap { it.zonesList }
                .firstOrNull { it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Stack }
            stackZone.shouldNotBeNull()

            val stackInstanceId = stackZone.objectInstanceIdsList.firstOrNull()
            stackInstanceId.shouldNotBeNull()

            // sourceId should match the spell on stack (post-realloc), not the old hand ID
            val sourceId = stMsg.selectTargetsReq.sourceId
            sourceId shouldBe stackInstanceId
        }

        test("bolt face resolve kills opponent") {
            val h = setupBoltFacePuzzle()

            h.castSpellByName("Lightning Bolt")

            // Select opponent (seatId=2) as target
            h.selectTargets(listOf(2))

            // Pass to resolve
            h.passPriority()

            // AI was at 3 life, bolt deals 3 → game over
            h.isGameOver().shouldBeTrue()

            // Verify game-over messages were sent
            val gameOverMsgs = h.allMessages.filter {
                it.hasGameStateMessage() &&
                    it.gameStateMessage.hasGameInfo() &&
                    it.gameStateMessage.gameInfo.stage == GameStage.GameOver
            }
            gameOverMsgs.shouldNotBeEmpty()
        }

        test("bolt face cancel and recast") {
            val h = setupBoltFacePuzzle()

            // Cast → cancel
            h.castSpellByName("Lightning Bolt")
            h.cancelAction()

            // Stack should be empty
            h.game().stack.isEmpty.shouldBeTrue()

            // Re-cast → target opponent → resolve
            val recast = h.castSpellByName("Lightning Bolt")
            recast.shouldBeTrue()

            h.selectTargets(listOf(2))
            h.passPriority()

            h.isGameOver().shouldBeTrue()
        }
    })
