package leyline.game.bundle

import forge.game.event.GameEventCardChangeZone
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

class StateFrameInputCaptureTest :
    BoardTest({
        test("supplied events stay open for the next ordinary cut") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            bridge.eventCollector!!.closeFrame()
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            game.fireEvent(
                GameEventCardChangeZone(
                    card,
                    game.humanPlayer.getZone(ZoneType.Battlefield),
                    game.humanPlayer.getZone(ZoneType.Graveyard),
                ),
            )

            val supplied =
                StateFrameInputCapture(bridge, "test-match", 1).capture(
                    game = game,
                    gameStateId = 21,
                    revealForSeat = null,
                    events =
                        StateFrameInputCapture.Events.Supplied(
                            FrameEventLog(listOf(GameEvent.LandPlayed(ForgeCardId(9_001), SeatId(1)))),
                        ),
                    includePreviousSnapshot = false,
                ) { _, _ -> GameStateUpdate.Send }

            supplied.state.events.events shouldBe listOf(GameEvent.LandPlayed(ForgeCardId(9_001), SeatId(1)))
            bridge.hasPendingEvents().shouldBeTrue()
        }

        test("ordinary event ownership closes the journal once") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            bridge.eventCollector!!.closeFrame()
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            game.fireEvent(
                GameEventCardChangeZone(
                    card,
                    game.humanPlayer.getZone(ZoneType.Battlefield),
                    game.humanPlayer.getZone(ZoneType.Graveyard),
                ),
            )

            val materialized =
                StateFrameInputCapture(bridge, "test-match", 1).capture(
                    game = game,
                    gameStateId = 21,
                    revealForSeat = null,
                    events = StateFrameInputCapture.Events.CloseBundleFrame,
                    includePreviousSnapshot = false,
                ) { _, _ -> GameStateUpdate.Send }

            assertSoftly {
                materialized.closesPlaybackFrame.shouldBeTrue()
                materialized
                    .state.events.events
                    .shouldHaveSize(1)
                bridge.hasPendingEvents().shouldBeFalse()
                bridge.closeBundleFrame(1).events.shouldBe(emptyList())
            }
        }
    })
