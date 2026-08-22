package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.*

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

        session(
            "kicker CTO decision hydrates faithfully (snapshot bytes == live bytes)",
            puzzle =
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
        ) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            val cto = allMessages.last { it.hasCastingTimeOptionsReq() }

            val live = advise(cto)
            val snapshot = advise(cto, leyline.tooling.headless.HeadlessAdviceMode.Snapshot)

            // Kicker/optional-cost decides by rebuilding the ability from the card
            // (cardForInstance -> getAllCastableAbilities), not from the in-flight
            // stack ability, so hydration carries enough to reproduce it exactly.
            // (Modal "choose one" CTO, by contrast, needs the bound stack SA and
            // does not hydrate faithfully — a distinct gap.)
            live.proposal.intent shouldBe "optional_cost"
            live.proposal.responses.shouldNotBeEmpty()
            snapshot.proposal.responses shouldBe live.proposal.responses
        }
    })
