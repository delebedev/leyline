package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId

/**
 * End-to-end blocker declaration tests: AI attacks, human blocks.
 *
 * Key insight: after declareNoAttackers() on human's combat, the
 * autoPassAndAdvance inside the submit handler processes the AI turn.
 * DO NOT call passPriority() to "advance" — it submits Pass to the
 * COMBAT_DECLARE_BLOCKERS pending, which means "no blockers" and
 * skips the entire DeclareBlockersReq flow.
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
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK)
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

            // Human combat: decline if prompted. The autoPassAndAdvance inside
            // declareNoAttackers processes the AI turn (land, cast, attack)
            // and may send DeclareBlockersReq in the same call.
            if (h.allMessages.any { it.hasDeclareAttackersReq() }) {
                h.declareNoAttackers()
            }

            // If DeclareBlockersReq isn't in messages yet, the AI turn hasn't
            // completed. Use bridge-level advanceTo to reach COMBAT_DECLARE_BLOCKERS
            // without intercepting the pending (passPriority would submit Pass
            // to the blocker pending = "no blockers"). Then trigger autoPassAndAdvance
            // directly — CombatHandler detects the combat phase and sends
            // DeclareBlockersReq before any action is submitted.
            if (!h.allMessages.any { it.hasDeclareBlockersReq() }) {
                leyline.game.advanceToPhase(h.bridge, "COMBAT_DECLARE_BLOCKERS")
                h.triggerAutoPass()
                h.drainSink()
            }

            h.allMessages.any { it.hasDeclareBlockersReq() }.shouldBeTrue()

            val humanCreatures = h.humanBattlefieldCreatures()
            humanCreatures.shouldNotBeEmpty()
            val blockerIid = humanCreatures.first().first

            // Find the AI attacker instanceId from the DeclareBlockersReq
            val blockReq = h.allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            (blockReq.blockersCount > 0).shouldBeTrue()

            // The blocker should reference attacker instanceIds
            val blocker = blockReq.blockersList.first { it.blockerInstanceId == blockerIid }
            (blocker.attackerInstanceIdsCount > 0).shouldBeTrue()
            val attackerIid = blocker.attackerInstanceIdsList.first()

            return blockerIid to attackerIid
        }

        test("human blocks AI attacker") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            val h = harness!!
            // Human life before blocking
            val humanPlayer = h.bridge.getPlayer(SeatId(1))!!
            val lifeBefore = humanPlayer.life

            // Declare block: human's Raging Goblin blocks AI's Raging Goblin
            h.declareBlockers(mapOf(blockerIid to attackerIid))

            h.passThroughCombat()

            // Human life should NOT decrease (blocked damage)
            val lifeAfter = humanPlayer.life
            lifeAfter shouldBe lifeBefore

            // Both 1/1s should have traded — human's creature should be in graveyard
            val humanGy = humanPlayer.getZone(ZoneType.Graveyard).cards
            val blockerInGy = humanGy.any { it.name == "Raging Goblin" }
            blockerInGy.shouldBeTrue()

            h.isGameOver().shouldBeFalse()
        }

        test("human declines blocking takes damage") {
            setupAiAttacksHumanCanBlock() // advances to DeclareBlockersReq

            val h = harness!!
            val humanPlayer = h.bridge.getPlayer(SeatId(1))!!
            val lifeBefore = humanPlayer.life

            // Human declines to block
            h.declareNoBlockers()

            h.passThroughCombat()

            // Human should have taken exactly 1 damage (Raging Goblin is 1/1)
            val lifeAfter = humanPlayer.life
            lifeAfter shouldBe lifeBefore - 1

            // Human's creature should still be alive
            val humanBf = h.humanBattlefieldCreatures()
            humanBf.shouldNotBeEmpty()

            h.isGameOver().shouldBeFalse()
        }

        test("trade produces creature deaths") {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            val h = harness!!
            // Declare block
            h.declareBlockers(mapOf(blockerIid to attackerIid))

            h.passThroughCombat()

            // Both creatures should be dead
            val humanPlayer = h.bridge.getPlayer(SeatId(1))!!
            val aiPlayer = h.bridge.getPlayer(SeatId(2))!!

            val humanGy = humanPlayer.getZone(ZoneType.Graveyard).cards
            val aiGy = aiPlayer.getZone(ZoneType.Graveyard).cards

            val humanGoblinDead = humanGy.any { it.name == "Raging Goblin" }
            val aiGoblinDead = aiGy.any { it.name == "Raging Goblin" }

            humanGoblinDead.shouldBeTrue()
            aiGoblinDead.shouldBeTrue()

            h.isGameOver().shouldBeFalse()
        }
    })
