package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Integration test for kicker — Burst Lightning kicked for 4 damage.
 *
 * Verifies: optional cost prompt fires, kicker cost auto-accepted,
 * spell deals kicked damage (4 not 2), targeting works.
 */
class KickerTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Burst Lightning kicked deals 4 damage for lethal") {
            val pzl = """
            [metadata]
            Name:Kick for Lethal
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Cast Burst Lightning with kicker for 4 damage.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=4

            humanhand=Burst Lightning
            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val human = h.game().registeredPlayers.first()
            val ai = h.game().registeredPlayers.last()

            h.phase() shouldBe "MAIN1"

            // Cast Burst Lightning — triggers optional cost prompt
            h.castSpellByName("Burst Lightning").shouldBeTrue()

            // Respond to kicker prompt: accept kicker (ctoId=1)
            val kickerResp = clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
                setCastingTimeOptionsResp(
                    CastingTimeOptionsResp.newBuilder()
                        .setCastingTimeOptionResp(
                            CastingTimeOptionResp.newBuilder().setCtoId(1),
                        ),
                )
            }
            h.session.onCastingTimeOptions(kickerResp)
            h.drainSink()

            // Burst Lightning targets "any target" — select opponent (seatId 2)
            h.selectTargets(listOf(2))

            // Resolve
            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            h.isGameOver().shouldBeTrue()
            human.hasWon().shouldBeTrue()
            human.hasLost().shouldBeFalse()
            // Kicked = 4 damage, AI was at 4 → should be 0
            ai.life shouldBe 0
        }
    })
