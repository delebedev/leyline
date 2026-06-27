package leyline.behavior.mechanics.saga

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import leyline.IntegrationTag
import leyline.bridge.types.ForgeCardId
import leyline.testkit.MatchFlowHarness
import leyline.testkit.assertConsistent
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Phase 2 — targeted chapter trigger (scope §2b).
 *
 * Teachings of the Kirin: Ch I mills + token (non-targeted), **Ch II puts a
 * +1/+1 counter on target creature you control** (mandatory targeted), Ch III
 * exile-return transforms.
 *
 * Load-bearing: Ch II fires a SelectTargetsReq that we must answer with our
 * one valid target (Grizzly Bears). Without the answer, the engine stalls on
 * the chapter ability on the stack.
 *
 * Same cast-from-hand session-tier shape as SagaTransformPuzzleTest — see its
 * KDoc for why board/puzzle-direct tiers don't work for saga triggers.
 */
class SagaTargetedChapterTest :
    FunSpec({

        tags(IntegrationTag)

        test("teachings of the kirin: targeted Ch II puts +1/+1 on sole valid target") {
            val puzzleText =
                """
                [metadata]
                Name:Saga Targeted Chapter — Teachings of the Kirin
                Goal:Survive
                Turns:6
                Difficulty:Easy
                Description:Cast Teachings of the Kirin, tick to Ch II, answer SelectTargetsReq with our sole creature.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Teachings of the Kirin
                humanbattlefield=Forest;Forest;Grizzly Bears
                humanlibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain
                """.trimIndent()

            val harness = MatchFlowHarness(validating = true)
            try {
                harness.connectAndKeepPuzzleText(puzzleText)
                val game = harness.bridge.getGame()!!

                val bear =
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first { it.name == "Grizzly Bears" }
                val bearIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(bear.id)).value

                harness.castSpellByName("Teachings of the Kirin").shouldBeTrue()

                // Turn 3 Main1 fires Ch II → SelectTargetsReq → submit bear.
                // Answer any SelectTargetsReq as it arrives during the tick-through.
                var bearHasCounter = false
                var lastSeenTargetReq = harness.allMessages.size
                harness.passUntil(maxPasses = 40) {
                    // Check for new SelectTargetsReq since last iteration.
                    val newSelectReq =
                        allMessages
                            .drop(lastSeenTargetReq)
                            .any { it.type == GREMessageType.SelectTargetsReq_695e }
                    lastSeenTargetReq = allMessages.size
                    if (newSelectReq) {
                        selectTargets(listOf(bearIid))
                    }

                    val liveBear =
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .firstOrNull { it.name == "Grizzly Bears" }
                    if (liveBear != null && liveBear.getCounters(CounterEnumType.P1P1) >= 1) {
                        bearHasCounter = true
                    }
                    bearHasCounter
                }

                withClue(
                    "turn=${harness.turn()} phase=${harness.phase()} " +
                        "stack=${game.stack.map { it.sourceCard.name }} " +
                        "selectReqs=${harness.allMessages.count { it.type == GREMessageType.SelectTargetsReq_695e }} " +
                        "bearCounters=${
                            game.humanPlayer
                                .getZone(ZoneType.Battlefield)
                                .cards
                                .firstOrNull { it.name == "Grizzly Bears" }
                                ?.getCounters(CounterEnumType.P1P1)
                        }",
                ) {
                    bearHasCounter.shouldBeTrue()
                }
                harness.accumulator.assertConsistent("after targeted Ch II")
            } finally {
                harness.shutdown()
            }
        }
    })
