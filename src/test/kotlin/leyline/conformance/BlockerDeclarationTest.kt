package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag

/**
 * End-to-end blocker declaration tests: AI attacks, human blocks.
 *
 * DISABLED: multi-turn setup takes 28-42s per test (~110s total for 2 enabled).
 * Needs puzzle-based rewrite: start with creatures on BF, script AI to attack.
 * Attempted puzzle migration but ScriptedPlayerController.declareAttackers()
 * doesn't fire in puzzle mode — autoPassAndAdvance skips through AI combat
 * without CombatHandler emitting DeclareBlockersReq. Root cause: puzzle-mode
 * AI controller interaction with AutoPassEngine needs investigation.
 *
 * Verifies:
 * - DeclareBlockersReq sent when AI attacks and human has eligible blockers
 * - Blocker assignments (human creature blocks AI creature) resolve correctly
 * - Declining to block lets damage through to human life
 * - 1/1 trading produces creature deaths (zone transfers)
 *
 * Uses non-validating harness: combat zone transfers can produce transient
 * instanceId gaps (known StateMapper issue tracked separately).
 */
class BlockerDeclarationTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        /**
         * Setup: human casts Raging Goblin turn 1 (potential blocker).
         * AI scripted to cast Raging Goblin and attack with it on its turn.
         * Advances to the point where DeclareBlockersReq should be sent.
         *
         * Returns pair of (humanBlockerInstanceId, aiAttackerInstanceId).
         */
        fun setupAiAttacksHumanCanBlock(): Pair<Int, Int> {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            // AI: play Mountain, cast Raging Goblin, attack with it
            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.CastSpell("Raging Goblin"),
                    ScriptedAction.Attack(listOf("Raging Goblin")),
                    ScriptedAction.PassPriority,
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Human turn 1: play Mountain, cast Raging Goblin (haste → potential blocker)
            h.playLand().shouldBeTrue()
            h.castSpellByName("Raging Goblin").shouldBeTrue()
            h.passPriority() // resolve

            // Skip human's own combat if prompted
            if (h.allMessages.any { it.hasDeclareAttackersReq() }) {
                h.declareNoAttackers()
            }

            // Get human blocker instanceId before combat
            val humanCreatures = h.humanBattlefieldCreatures()
            humanCreatures.shouldNotBeEmpty()
            val blockerIid = humanCreatures.first().first

            // Advance to AI's turn — AI script runs: land, cast, attack.
            var sawBlockerReq = false
            for (i in 0 until 50) {
                if (h.isGameOver()) break
                val snap = h.messageSnapshot()
                h.passPriority()
                val recent = h.messagesSince(snap)
                if (recent.any { it.hasDeclareBlockersReq() }) {
                    sawBlockerReq = true
                    break
                }
                if (recent.any { it.hasDeclareAttackersReq() }) {
                    h.declareNoAttackers()
                }
            }

            sawBlockerReq.shouldBeTrue()

            // Find the AI attacker instanceId from the DeclareBlockersReq
            val blockReq = h.allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            (blockReq.blockersCount > 0).shouldBeTrue()

            // The blocker should reference attacker instanceIds
            val blocker = blockReq.blockersList.first { it.blockerInstanceId == blockerIid }
            (blocker.attackerInstanceIdsCount > 0).shouldBeTrue()
            val attackerIid = blocker.attackerInstanceIdsList.first()

            return blockerIid to attackerIid
        }

        // TODO: flaky — setupAiAttacksHumanCanBlock loop doesn't reliably reach
        //  DeclareBlockersReq within iteration budget. AI script timing is seed-sensitive.
        //  Pre-existing issue (fails 1/3 on old code too). Needs deterministic puzzle-based setup.
        xtest("human blocks AI attacker") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            val h = harness!!
            // Human life before blocking
            val humanPlayer = h.bridge.getPlayer(1)!!
            val lifeBefore = humanPlayer.life

            // Declare block: human's Raging Goblin blocks AI's Raging Goblin
            h.declareBlockers(mapOf(blockerIid to attackerIid))

            // Pass through remaining combat
            repeat(15) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            // Human life should NOT decrease (blocked damage)
            val lifeAfter = humanPlayer.life
            lifeAfter shouldBe lifeBefore

            // Both 1/1s should have traded — human's creature should be in graveyard
            val humanGy = humanPlayer.getZone(ZoneType.Graveyard).cards
            val blockerInGy = humanGy.any { it.name == "Raging Goblin" }
            blockerInGy.shouldBeTrue()

            h.isGameOver().shouldBeFalse()
        }

        xtest("human declines blocking takes damage") {
            setupAiAttacksHumanCanBlock() // advances to DeclareBlockersReq

            val h = harness!!
            val humanPlayer = h.bridge.getPlayer(1)!!
            val lifeBefore = humanPlayer.life

            // Human declines to block
            h.declareNoBlockers()

            // Pass through remaining combat
            repeat(15) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            // Human should have taken exactly 1 damage (Raging Goblin is 1/1)
            val lifeAfter = humanPlayer.life
            lifeAfter shouldBe lifeBefore - 1

            // Human's creature should still be alive
            val humanBf = h.humanBattlefieldCreatures()
            humanBf.shouldNotBeEmpty()

            h.isGameOver().shouldBeFalse()
        }

        xtest("trade produces creature deaths") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            val h = harness!!
            // Declare block
            h.declareBlockers(mapOf(blockerIid to attackerIid))

            // Pass through remaining combat
            repeat(15) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            // Both creatures should be dead
            val humanPlayer = h.bridge.getPlayer(1)!!
            val aiPlayer = h.bridge.getPlayer(2)!!

            val humanGy = humanPlayer.getZone(ZoneType.Graveyard).cards
            val aiGy = aiPlayer.getZone(ZoneType.Graveyard).cards

            val humanGoblinDead = humanGy.any { it.name == "Raging Goblin" }
            val aiGoblinDead = aiGy.any { it.name == "Raging Goblin" }

            humanGoblinDead.shouldBeTrue()
            aiGoblinDead.shouldBeTrue()

            h.isGameOver().shouldBeFalse()
        }
    })
