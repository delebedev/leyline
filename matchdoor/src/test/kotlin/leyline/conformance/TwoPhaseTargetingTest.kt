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
 * Conformance test for two-phase targeting protocol.
 *
 * Wire spec: docs/plans/2026-03-14-submit-targets-wire-spec.md
 *
 * Phase 1: SelectTargetsResp → server stores selection, sends echo GSM + re-prompt
 * Phase 2: SubmitTargetsReq → server submits to engine, resolves
 */
class TwoPhaseTargetingTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Lightning Bolt two-phase targeting: select → re-prompt → submit → resolve") {
            val pzl = """
            [metadata]
            Name:Bolt Conformance
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Bolt face with two-phase targeting

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanhand=Lightning Bolt
            humanbattlefield=Mountain
            humanlibrary=Mountain
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val ai = h.game().registeredPlayers.last()

            // Cast Lightning Bolt
            h.castSpellByName("Lightning Bolt").shouldBeTrue()

            // Phase 1: Send SelectTargetsResp (target opponent = seatId 2)
            val snap = h.messageSnapshot()
            h.selectTargetsIterative(listOf(2))
            val echoMessages = h.messagesSince(snap)

            // Verify echo-back contains a re-prompt SelectTargetsReq
            val rePromptMsg = echoMessages.firstOrNull { it.hasSelectTargetsReq() }
            rePromptMsg.shouldNotBeNull()

            val rePrompt = rePromptMsg.selectTargetsReq
            rePrompt.targetsCount shouldBeGreaterThan 0

            val targetGroup = rePrompt.getTargets(0)
            // Re-prompt should show selected target as Unselect
            targetGroup.targetsList.size shouldBe 1
            targetGroup.targetsList[0].targetInstanceId shouldBe 2
            targetGroup.targetsList[0].legalAction shouldBe SelectAction.Unselect
            targetGroup.selectedTargets shouldBe 1

            // Phase 2: Send SubmitTargetsReq
            h.submitTargets()

            // Resolve
            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            h.isGameOver().shouldBeTrue()
            ai.life shouldBe 0
        }

        test("phase 1 does not resolve spell before submit") {
            val pzl = """
            [metadata]
            Name:Bolt Two-Phase Gate
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Selection alone must not resolve until submit.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanhand=Lightning Bolt
            humanbattlefield=Mountain
            humanlibrary=Mountain
            ailibrary=Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            val ai = h.game().registeredPlayers.last()

            h.castSpellByName("Lightning Bolt").shouldBeTrue()

            val phase1Snap = h.messageSnapshot()
            h.selectTargetsIterative(listOf(2))
            val phase1Messages = h.messagesSince(phase1Snap)

            ai.life shouldBe 3
            h.isGameOver().shouldBeFalse()
            phase1Messages.any { it.hasSubmitTargetsResp() }.shouldBeFalse()
            phase1Messages.any { it.hasSelectTargetsReq() }.shouldBeTrue()

            val phase2Snap = h.messageSnapshot()
            h.submitTargets()
            val phase2Messages = h.messagesSince(phase2Snap)

            phase2Messages.any { it.hasSubmitTargetsResp() }.shouldBeTrue()

            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            h.isGameOver().shouldBeTrue()
            ai.life shouldBe 0
        }
    })
