package leyline.conformance

import leyline.bridge.SeatId
import leyline.config.AiConfig
import leyline.config.MatchConfig
import leyline.game.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Test harness for two-human-player matches — zero reimplemented logic.
 *
 * Creates one [GameBridge] with two [MatchSession]s (seat 1 + seat 2),
 * each with its own [ListMessageSink]. Both sessions share the same bridge,
 * [MatchRegistry], and [MessageCounter] (same as production PvP).
 *
 * Uses skipMulligan — both seats auto-keep, no mulligan interaction.
 */
class DualSeatHarness(
    private val seed: Long = 42L,
    private val deckList1: String? = null,
    private val deckList2: String? = null,
) {
    private val matchId = "test-pvp-match"

    val registry = MatchRegistry()
    val sink1 = ListMessageSink()
    val sink2 = ListMessageSink()

    lateinit var session1: MatchSession
        private set
    lateinit var session2: MatchSession
        private set
    lateinit var bridge: GameBridge
        private set

    val seat1Messages = mutableListOf<GREToClientMessage>()
    val seat2Messages = mutableListOf<GREToClientMessage>()

    /**
     * Start two-player game, connect both sessions, send initial state to both.
     * After this call, both sinks contain GSMs for their seat with correct visibility.
     */
    fun connectBothSeats() {
        leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()

        bridge = GameBridge(
            bridgeTimeoutMs = 5_000L,
            matchConfig = MatchConfig(ai = AiConfig(speed = 0.0)),
            cards = TestCardRegistry.repo,
        )
        bridge.priorityWaitMs = 2_000L
        bridge.startTwoPlayer(seed = seed, deckList1 = deckList1, deckList2 = deckList2)

        session1 = MatchSession(
            seatId = SeatId(1),
            matchId = matchId,
            sink = sink1,
            registry = registry,
            paceDelayMs = 0,
            counter = bridge.messageCounter,
        )
        session2 = MatchSession(
            seatId = SeatId(2),
            matchId = matchId,
            sink = sink2,
            registry = registry,
            paceDelayMs = 0,
            counter = bridge.messageCounter,
        )

        session1.connectBridge(bridge)
        session2.connectBridge(bridge)
        registry.registerSession(matchId, 1, session1)
        registry.registerSession(matchId, 2, session2)

        // Send initial game state to both seats (per-seat visibility filtering)
        session1.sendRealGameState(bridge)
        session2.sendRealGameState(bridge)

        drainBothSinks()
    }

    fun drainBothSinks() {
        seat1Messages.addAll(sink1.messages)
        seat2Messages.addAll(sink2.messages)
        sink1.clear()
        sink2.clear()
    }

    /** All GameStateMessages from a seat's message list. */
    fun allGsms(messages: List<GREToClientMessage>): List<GameStateMessage> =
        messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }

    fun shutdown() = bridge.shutdown()
}
