package leyline.game.bundle

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.game.event.FrameEventLog
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Validates BundleBuilder output shape matches client patterns.
 *
 * Message types, update type, prompt IDs — all asserted directly against the
 * raw GRE messages.
 *
 * Uses startWithBoard for fast synchronous setup (~0.01s).
 */
class ShapeIntegrationTest :
    BoardTest({

        test("playback cut produces content SendHiFi GSM plus bare SendHiFi echo") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                }

            val builder = bundleBuilder(b)
            val cut = builder.materializePlaybackCut(game, counter, turnStarted = false, events = FrameEventLog.EMPTY)
            val prepared = builder.compilePlaybackCut(cut)
            b.commitProjection(prepared.transition)
            val messages = prepared.batches.first()

            messages.size shouldBe 2
            val echo = messages[1].gameStateMessage
            assertSoftly {
                messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                messages[1].type shouldBe GREMessageType.GameStateMessage_695e
                messages[0].gameStateMessage.update shouldBe GameStateUpdate.SendHiFi
                messages[1].gameStateMessage.update shouldBe GameStateUpdate.SendHiFi
                echo.annotationsCount shouldBe 0
                echo.persistentAnnotationsCount shouldBe 0
                echo.gameObjectsCount shouldBe 0
                echo.zonesCount shouldBe 0
            }
        }

        test("declareAttackersBundle produces GS + DeclareAttackersReq with promptId=6") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }

            val messages = bundleBuilder(b).declareAttackersBundle(game, counter).messages

            assertSoftly {
                messages.size shouldBe 2
                messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
                messages[1].prompt.promptId shouldBe 6
            }
        }
    })
