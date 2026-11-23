package leyline.session.combat

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient

private const val INVASION_PLANS_BOARD = """
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20
humanbattlefield=Invasion Plans;Grizzly Bears
humanlibrary=Forest;Forest;Forest
aibattlefield=Walking Corpse
ailibrary=Island;Island;Island
"""

private const val MASTER_WARCRAFT_BOARD = """
ActivePlayer=AI
ActivePhase=Main1
HumanLife=20
AILife=20
humanhand=Master Warcraft
humanbattlefield=Mountain;Mountain;Mountain;Mountain;Grizzly Bears
humanlibrary=Forest;Forest;Forest
aibattlefield=Centaur Courser
ailibrary=Island;Island;Island
"""

private fun MatchFlowHarness.battlefieldIid(
    seat: Int,
    name: String,
): Int =
    bridge.instanceId(
        checkNotNull(bridge.getPlayer(SeatId(seat)))
            .getZone(ZoneType.Battlefield)
            .cards
            .single { it.name == name },
    )

private fun playerRecipient(seat: Int): DamageRecipient =
    DamageRecipient
        .newBuilder()
        .setType(DamageRecType.Player_a0e5)
        .setPlayerSystemSeatId(seat)
        .build()

/**
 * A player who declares another player's combatants (Invasion Plans,
 * Master Warcraft) is offered the combatant's creatures, and the engine
 * commits only creatures that player controls.
 */
class DelegatedCombatDeclarationTest :
    SessionTest({
        session(
            "attacking player assigns the defending player's creatures as blockers",
            puzzle = INVASION_PLANS_BOARD,
            turns = 4,
        ) {
            val bears = battlefieldIid(HUMAN_SEAT, "Grizzly Bears")
            val corpse = battlefieldIid(OPPONENT_SEAT, "Walking Corpse")
            passUntil(maxPasses = 20) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(bears))
            passUntil(maxPasses = 20) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()

            val request = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            request.blockersList.map { it.blockerInstanceId } shouldContainExactly listOf(corpse)
            request.blockersList.single().attackerInstanceIdsList shouldContainExactly listOf(bears)

            declareBlockers(mapOf(corpse to bears))
            passUntil(maxPasses = 40) {
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Grizzly Bears" }
            }.shouldBeTrue()
            assertSoftly {
                ai.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContainExactly listOf("Walking Corpse")
                human.life shouldBe 20
                ai.life shouldBe 20
            }
        }

        session(
            "defending player declares their own blockers without a delegated declarer",
            puzzle = INVASION_PLANS_BOARD.replace("Invasion Plans;", ""),
            turns = 4,
        ) {
            val bears = battlefieldIid(HUMAN_SEAT, "Grizzly Bears")
            passUntil(maxPasses = 20) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(bears))
            passUntil(maxPasses = 40) { ai.life == 18 }.shouldBeTrue()
            allMessages.count { it.hasDeclareBlockersReq() } shouldBe 0
        }

        session(
            "player who chooses attackers is offered and commits only the attacking player's creatures",
            puzzle = MASTER_WARCRAFT_BOARD,
            turns = 4,
            aiScript = listOf(ScriptedAction.PassPriority),
        ) {
            val bears = battlefieldIid(HUMAN_SEAT, "Grizzly Bears")
            val courser = battlefieldIid(OPPONENT_SEAT, "Centaur Courser")
            castSpellByName("Master Warcraft").shouldBeTrue()
            passUntil(maxPasses = 20) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            val request = allMessages.last { it.hasDeclareAttackersReq() }.declareAttackersReq
            request.attackersList.map { it.attackerInstanceId }.distinct() shouldContainExactly listOf(courser)
            request.attackersList
                .single()
                .legalDamageRecipientsList shouldContainExactly listOf(playerRecipient(HUMAN_SEAT))

            toggleAttackers(listOf(bears), damageRecipients = mapOf(bears to playerRecipient(OPPONENT_SEAT)))
            allMessages
                .last { it.hasDeclareAttackersReq() }
                .declareAttackersReq.attackersList
                .none { it.hasSelectedDamageRecipient() }
                .shouldBeTrue()

            declareAttackers(listOf(courser), mapOf(courser to playerRecipient(HUMAN_SEAT)))
            passUntil(maxPasses = 40) { human.life == 17 }.shouldBeTrue()
            val bearsCard = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Grizzly Bears" }
            assertSoftly {
                bearsCard.controller shouldBe human
                bearsCard.isTapped.shouldBeFalse()
                ai.life shouldBe 20
            }
        }

        session(
            "attacking player declares their own attackers without a delegated declarer",
            puzzle = MASTER_WARCRAFT_BOARD,
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Centaur Courser")), ScriptedAction.PassPriority),
        ) {
            passUntil(maxPasses = 40) { human.life == 17 }.shouldBeTrue()
            assertSoftly {
                allMessages.count { it.hasDeclareAttackersReq() } shouldBe 0
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Master Warcraft"
                ai.life shouldBe 20
            }
        }
    })
