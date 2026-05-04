package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.game.InMemoryCardRepository
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
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
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("remoteActionDiff produces content SendHiFi GSM plus bare SendHiFi echo") {
            val (b, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Battlefield)
                }

            val messages = base.bundleBuilder(b).remoteActionDiff(game, counter).messages

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
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }

            val messages = base.bundleBuilder(b).declareAttackersBundle(game, counter).messages

            assertSoftly {
                messages.size shouldBe 2
                messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
                messages[1].prompt.promptId shouldBe 6
            }
        }

        test("edictalPass produces single EdictalMessage") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val messages =
                BundleBuilder(bridge, "test-match", 1)
                    .edictalPass(MessageCounter(initialGsId = 10, initialMsgId = 0))
                    .messages

            messages.size shouldBe 1
            messages[0].type shouldBe GREMessageType.EdictalMessage_695e
        }
    })
