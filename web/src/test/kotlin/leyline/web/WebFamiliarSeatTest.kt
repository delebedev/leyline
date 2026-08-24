package leyline.web

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.service.MatchCoordinator
import leyline.game.InMemoryCardRepository
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A browser drives one seat. Seat 2 — whose starting-player answer is what
 * releases the opening hands — is supplied by the server, because the web relay
 * multiplexes every browser socket onto a single engine and so cannot carry a
 * second seat.
 */
class WebFamiliarSeatTest :
    FunSpec({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        val deck = "60 Forest"

        fun matchConfig() =
            EngineSettings(
                seed = 42L,
                dieRollWinner = 1,
                skipMulligan = false,
                bridgeTimeoutMs = 2_000L,
                promptFailsafeMs = 2_000L,
                aiTurnWaitMs = 2_000L,
                mulliganWaitMs = 2_000L,
            )

        fun greTypes(frames: List<ByteArray>): List<GREMessageType> =
            frames
                .map(MatchServiceToClientMessage::parseFrom)
                .flatMap { it.greToClientEvent.greToClientMessagesList }
                .map { it.type }

        fun engine(
            matchId: String,
            frames: MutableList<ByteArray>,
        ): DirectWebGreEngineSession {
            val configs = RuntimeMatchConfigRegistry()
            configs.put(RuntimeMatchConfig(matchId = matchId, seat1Deck = deck, seat2Deck = deck))
            return DirectWebGreEngineSession(
                matchConfig(),
                MatchCoordinator.NOOP,
                InMemoryCardRepository(),
                configs,
                frames::add,
                puzzlesDir = java.io.File("."),
            )
        }

        test("a single-seat browser is dealt its opening hand") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val matchId = "web-familiar-deal"
            val session = engine(matchId, frames)

            try {
                session.receiveFromBrowser(authRequestBytes("web-player"))
                session.receiveFromBrowser(connectRequestBytes(matchId, seatId = 1))

                val types = greTypes(frames)
                assertSoftly {
                    // The handshake the browser drove itself.
                    types shouldContain GREMessageType.ConnectResp_695e
                    types shouldContain GREMessageType.GameStateMessage_695e
                    // Released only by the opponent seat's starting-player answer,
                    // and exactly once — a second Familiar would deal twice.
                    types.count { it == GREMessageType.MulliganReq_aa0d } shouldBe 1
                    // Seat 2's prompt belongs to seat 2 — the browser never sees it.
                    types shouldNotContain GREMessageType.ChooseStartingPlayerReq_695e
                }
            } finally {
                session.close()
            }
        }

        test("reconnecting the browser does not re-run the opponent seat") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val matchId = "web-familiar-reconnect"
            val session = engine(matchId, frames)

            try {
                session.receiveFromBrowser(authRequestBytes("web-player"))
                session.receiveFromBrowser(connectRequestBytes(matchId, seatId = 1))
                val dealt = greTypes(frames).count { it == GREMessageType.MulliganReq_aa0d }

                // Page reload: the same socket-level client hands the engine a
                // second handshake, which re-seats seat 1 and resyncs it.
                frames.clear()
                session.receiveFromBrowser(authRequestBytes("web-player"))
                session.receiveFromBrowser(connectRequestBytes(matchId, seatId = 1))

                assertSoftly {
                    dealt shouldBe 1
                    greTypes(frames) shouldContain GREMessageType.GameStateMessage_695e
                    // A second Familiar join would deal a second opening hand.
                    greTypes(frames) shouldNotContain GREMessageType.MulliganReq_aa0d
                }
            } finally {
                session.close()
            }
        }
    })
