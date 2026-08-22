package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.*
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Drives target declaration entirely off consult proposals against the live
 * (validating) session — the targeting analogue of TwoPhaseCombatFidelityTest.
 * SelectTargetsReq is iterative-delta: the consult picks the target, the session
 * echoes a fresh SelectTargetsReq, and a SubmitTargetsReq answers the re-prompt.
 * Each response's respId must echo the answering prompt's msgId; fidelity mode
 * rejects a mismatch, so a wrong envelope fails the walk.
 *
 * Proves the two-round-trip end to end: a consult-driven Giant Growth resolves
 * (the creature is buffed) with zero IllegalRequest.
 */
@Suppress("MissingAssertSoftly")
class ConsultTargetingFidelityTest :
    SessionTest({

        session("consult-driven Giant Growth resolves via select-then-submit under fidelity mode", puzzleFile = "puzzles/pump-spell.pzl") {
            val creatureIid = humanBattlefieldCreatures().first().first
            castSpellByName("Giant Growth").shouldBeTrue()
            drainSink()
            allMessages.any { it.hasSelectTargetsReq() }.shouldBeTrue()

            val prompt = allMessages.last { it.hasSelectTargetsReq() }
            selectTargets(listOf(creatureIid))
            allMessages.last { it.msgId > prompt.msgId }.msgId shouldBeGreaterThan prompt.msgId
            submitTargets()

            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }.shouldBeTrue()

            val creature = cardByIid(creatureIid).shouldNotBeNull()
            creature.netPower shouldBeGreaterThanOrEqual 4
            human
                .getZone(ForgeZoneType.Graveyard)
                .cards
                .count { it.name == "Giant Growth" } shouldBe 1
            allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
        }
    })
