package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Integration test for kicker — Burst Lightning kicked for 4 damage.
 *
 * Verifies:
 * 1. CastingTimeOptionsReq prompt sent with Kicker type (conformance)
 * 2. Kicker cost accepted via response
 * 3. Spell deals kicked damage (4 not 2)
 *
 * Wire spec: docs/plans/2026-03-14-kicker-wire-spec.md
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
            val snap = h.messageSnapshot()
            h.castSpellByName("Burst Lightning").shouldBeTrue()
            val castMessages = h.messagesSince(snap)

            // --- Conformance: verify CastingTimeOptionsReq shape ---
            val ctoReqMsg = castMessages.firstOrNull { it.hasCastingTimeOptionsReq() }
            ctoReqMsg.shouldNotBeNull()

            val ctoReq = ctoReqMsg.castingTimeOptionsReq
            ctoReq.castingTimeOptionReqCount shouldBeGreaterThan 1

            // Find kicker option (type = Kicker, ctoId > 0)
            val kickerOption = ctoReq.castingTimeOptionReqList.firstOrNull {
                it.castingTimeOptionType == CastingTimeOptionType.Kicker
            }
            kickerOption.shouldNotBeNull()
            kickerOption.ctoId shouldBeGreaterThan 0

            // Find Done option (type = Done, ctoId = 0)
            val doneOption = ctoReq.castingTimeOptionReqList.firstOrNull {
                it.castingTimeOptionType == CastingTimeOptionType.Done
            }
            doneOption.shouldNotBeNull()
            doneOption.ctoId shouldBe 0
            doneOption.isRequired.shouldBeTrue()

            // --- Accept kicker ---
            val kickerResp = clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
                setCastingTimeOptionsResp(
                    CastingTimeOptionsResp.newBuilder()
                        .setCastingTimeOptionResp(
                            CastingTimeOptionResp.newBuilder().setCtoId(kickerOption.ctoId),
                        ),
                )
            }
            h.session.onCastingTimeOptions(kickerResp)
            h.drainSink()

            // Target opponent (seatId 2)
            h.selectTargets(listOf(2))

            // Resolve
            h.passUntil(maxPasses = 20) { isGameOver() || ai.life <= 0 }.shouldBeTrue()

            h.isGameOver().shouldBeTrue()
            human.hasWon().shouldBeTrue()
            human.hasLost().shouldBeFalse()
            ai.life shouldBe 0
        }

        test("optional cost response gates targeting prompt") {
            val pzl = """
            [metadata]
            Name:Kick Then Target
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Optional-cost prompt must resolve before target selection prompt.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=5

            humanhand=Burst Lightning
            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Mountain
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val castSnap = h.messageSnapshot()
            h.castSpellByName("Burst Lightning").shouldBeTrue()
            val castMessages = h.messagesSince(castSnap)

            val ctoReqMsg = castMessages.firstOrNull { it.hasCastingTimeOptionsReq() }
            ctoReqMsg.shouldNotBeNull()
            castMessages.any { it.hasSelectTargetsReq() }.shouldBeFalse()

            val doneOption = ctoReqMsg.castingTimeOptionsReq.castingTimeOptionReqList.first {
                it.castingTimeOptionType == CastingTimeOptionType.Done
            }

            val targetSnap = h.messageSnapshot()
            val doneResp = clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
                setCastingTimeOptionsResp(
                    CastingTimeOptionsResp.newBuilder()
                        .setCastingTimeOptionResp(
                            CastingTimeOptionResp.newBuilder().setCtoId(doneOption.ctoId),
                        ),
                )
            }
            h.session.onCastingTimeOptions(doneResp)
            h.drainSink()

            val targetMessages = h.messagesSince(targetSnap)
            targetMessages.any { it.hasSelectTargetsReq() }.shouldBeTrue()
        }
    })
