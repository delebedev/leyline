package leyline.session.turns

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.*
import leyline.testkit.AI_FIRST_SEED
import leyline.testkit.SessionTest
import leyline.testkit.detail
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Stream contract: a turn-boundary or trigger-driven own-seat draw must be
 * emitted with [GameStateUpdate.SendHiFi]. Spell-driven Main1 draws continue
 * to use [GameStateUpdate.SendAndRecord] (covered by the bundle unit tests).
 */
// TierPlacementCheck: the auto-pass that drives AI turn 1 → human turn 2 is
// precisely the session-level behavior under test — the bundle needs to
// observe a real turn-boundary draw event delivered by the engine's EventBus,
// not a synthetic one. Subsystem-tier helpers (buildActions / stateOnlyDiff)
// bypass that code path and would not exercise the override.
@Suppress("TierPlacementCheck")
class DrawUpdateTypeShapeTest :
    SessionTest({

        session("turn-boundary DRAW for human seat uses SendHiFi", deckList = "60 Mountain", seed = AI_FIRST_SEED) {
            assertSoftly {
                isGameOver() shouldBe false
                turn() shouldBe 2
                phase() shouldBe "MAIN1"
            }

            // The first own-seat Library→Hand ZoneTransfer we see in the message log
            // is the turn-2 turn-boundary draw. Under the fix it must carry SendHiFi;
            // pre-fix it carries SendAndRecord.
            val drawGsm =
                allMessages
                    .gameStateMessages()
                    .firstOrNull { gsm ->
                        gsm.annotationsList.any { ann ->
                            AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                                ann.detail("zone_src")?.getValueInt32(0) == ZoneIds.libraryOf(1) &&
                                ann.detail("zone_dest")?.getValueInt32(0) == ZoneIds.handOf(1)
                        }
                    }

            drawGsm.shouldNotBeNull()
            drawGsm.update shouldBe GameStateUpdate.SendHiFi
        }
    })
