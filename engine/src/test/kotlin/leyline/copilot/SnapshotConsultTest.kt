package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry

/**
 * End-to-end consult: serialize a running game's GSM, hand it with the game's
 * own pending prompt to [SnapshotConsult], and verify the proposal comes back
 * in the SOURCE game's id space (instanceIds from the source GSM, not the
 * hydrated game's own allocations) with a position eval attached.
 */
@Suppress("MissingAssertSoftly")
class SnapshotConsultTest :
    SessionTest({

        test("consult proposes the lethal bolt in source-game ids with eval") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=3

                humanhand=Lightning Bolt
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // The harness seeds its initial Full GSM into the accumulator rather
            // than the message log; rebuild the same wire Full GSM here.
            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm
            val aar = allMessages.last { it.hasActionsAvailableReq() }

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = aar,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "cast"
            val boltGrpId = TestCardRegistry.repo.findGrpIdByName("Lightning Bolt").shouldNotBeNull()
            val card = result.proposal.card.shouldNotBeNull()
            card.grpId shouldBe boltGrpId

            // The load-bearing assertion: the proposal references the SOURCE
            // game's instanceId for the bolt, so the response is submittable
            // against the source game as-is.
            val sourceBoltIids =
                gsm.gameObjectsList.filter { it.grpId == boltGrpId }.map { it.instanceId }
            sourceBoltIids.shouldNotBeEmpty()
            (card.instanceId in sourceBoltIids) shouldBe true

            result.proposal.responses.shouldNotBeEmpty()
            result.eval.shouldNotBeNull()
        }

        test("consult proposes a cast after the source game's land drop is spent") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Land Drop
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Raging Goblin;Mountain
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // Spend the land drop in the source game; the follow-up prompt
            // offers casts only. Hydration resets the drop, so the consult
            // must re-derive "drop spent" from the prompt — otherwise the AI
            // holds every spell waiting to play a land it can no longer play.
            harness.playLand()
            val aar = allMessages.last { it.hasActionsAvailableReq() }
            aar.actionsAvailableReq.actionsList.none {
                it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Play_add3
            } shouldBe
                true

            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = aar,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "cast"
            result.proposal.card
                .shouldNotBeNull()
                .name shouldBe "Raging Goblin"
        }

        test("consult targets the OPPONENT player for a player-burn spell") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Player Target
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Lava Axe
                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain
                ailibrary=Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // Cast in the source game up to the targeting prompt, then consult
            // the snapshot about that prompt. The hydrated game has no bound
            // targeting ability, so this exercises the required-target path
            // where both candidates are players — the pick must be the
            // opponent's face (seat 2), never our own.
            castSpellByName("Lava Axe").shouldBeTrue()
            val targetsReq = allMessages.last { it.hasSelectTargetsReq() }

            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = targetsReq,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "target"
            result.proposal.responseIds shouldBe listOf(2)
        }

        test("target consult reproduces the live decision byte-for-byte (rebuilt ability)") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Target Fidelity
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Raging Goblin;Centaur Courser
                ailibrary=Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // Multiple legal targets (two enemy creatures + both faces): the
            // pre-rebuild fallback picked by list order, not by the AI's
            // judgement. The contract under test: the hydrated consult routes
            // through the same ability the live game consults, so the response
            // bytes are identical — whatever the AI's pick is.
            castSpellByName("Shock").shouldBeTrue()
            val targetsReq = allMessages.last { it.hasSelectTargetsReq() }

            val sourceBridge = harness.bridge
            val live = CopilotProposalService(sourceBridge, leyline.bridge.types.SeatId(1)).propose(targetsReq)
            live.responses.shouldNotBeEmpty()

            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm
            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = targetsReq,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.responses shouldBe live.responses
        }
    })
