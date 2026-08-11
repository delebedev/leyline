package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry

/**
 * Exploratory: does the CastingTimeOptions (kicker) decision hydrate faithfully?
 * Stage a kicker spell, reach the kicker CTO prompt, then compare the response
 * the decision brain produces on the live game vs a game hydrated from its wire
 * state — the same comparison the snapshot-shadow probe makes, but on a prompt
 * family the deck matrix did not exercise.
 */
@Suppress("MissingAssertSoftly")
class CtoHydrationProbeTest :
    SessionTest({

        test("kicker CTO decision hydrates faithfully (snapshot bytes == live bytes)") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Burst Lightning
                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """.trimIndent(),
                name = "Burst Lightning",
            )

            castSpellByName("Burst Lightning").shouldBeTrue()
            val cto = allMessages.last { it.hasCastingTimeOptionsReq() }

            val live = CopilotProposalService(harness.bridge, SeatId(1)).propose(cto)

            val snap = GsmSnapshot.capture(harness.bridge.getGame()!!, harness.bridge, "probe", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "probe", harness.bridge, viewingSeatId = 1, events = FrameEventLog(emptyList()))
                    .gsm
            val snapshot = SnapshotConsult.consult(gsm, cto, 1, TestCardRegistry.repo).proposal

            // Kicker/optional-cost decides by rebuilding the ability from the card
            // (cardForInstance -> getAllCastableAbilities), not from the in-flight
            // stack ability, so hydration carries enough to reproduce it exactly.
            // (Modal "choose one" CTO, by contrast, needs the bound stack SA and
            // does not hydrate faithfully — a distinct gap.)
            live.intent shouldBe "optional_cost"
            live.responses.shouldNotBeEmpty()
            snapshot.responses shouldBe live.responses
        }
    })
