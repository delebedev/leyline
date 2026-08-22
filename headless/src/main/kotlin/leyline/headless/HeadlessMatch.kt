package leyline.headless

import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.AiConfig
import leyline.config.GameConfig
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.config.ServerConfig
import leyline.game.data.AutoMappingCardRepository
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import leyline.match.MatchRegistry
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/** In-process client that drives one puzzle match through [MatchConnection]. */
class HeadlessMatch private constructor(
    private val driver: HeadlessMatchDriver,
) : AutoCloseable {
    private val matchId: String get() = driver.matchId
    private val output: QueueingMatchOutput get() = driver.output
    private val connection: MatchConnection get() = driver.connection
    private var connected = false
    private var closed = false
    val client = HeadlessClient.create()
    val engine = HeadlessEngine.create(driver.registry, matchId)

    /** Establish match identity and return the initial GRE bundle. */
    fun connect(): List<GREToClientMessage> {
        check(!connected) { "Headless match is already connected" }
        check(!closed) { "Headless match is closed" }

        connection.receive(authenticateRequest())
        client.observe(output.drain())
        connected = true
        connection.receive(connectRequest())
        connection.awaitQuiescence()
        return drainObservedGre()
    }

    /** Submit one parsed GRE response and return all output published before quiescence. */
    fun submit(message: ClientToGREMessage): List<GREToClientMessage> {
        check(connected) { "Call connect() before submit()" }
        check(!closed) { "Headless match is closed" }
        client.submitted()
        connection.submitGREMessage(message)
        return drainObservedGre()
    }

    /** Submit the Pass action currently offered to the local seat. */
    fun pass(): List<GREToClientMessage> {
        val prompt = checkNotNull(client.pendingActions) { "No ActionsAvailableReq is pending" }
        val action = prompt.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Pass }
        return submit(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.PerformActionResp_097b)
                .setGameStateId(prompt.gameStateId)
                .setRespId(prompt.msgId)
                .setPerformActionResp(PerformActionResp.newBuilder().addActions(action))
                .build(),
        )
    }

    /** Concede the local seat and return the client-visible completion sequence. */
    fun concede(): List<GREToClientMessage> =
        submit(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.ConcedeReq_097b)
                .build(),
        )

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (connected) connection.disconnected()
        } finally {
            output.close()
        }
    }

    private fun drainObservedGre(): List<GREToClientMessage> {
        val messages = output.drain()
        client.observe(messages)
        return messages
            .asSequence()
            .filter { it.hasGreToClientEvent() }
            .flatMap { it.greToClientEvent.greToClientMessagesList.asSequence() }
            .toList()
    }

    private fun authenticateRequest(): ClientToMatchServiceMessage =
        serviceMessage(
            type = ClientToMatchServiceMessageType.AuthenticateRequest_f487,
            requestId = 1,
            payload =
                AuthenticateRequest
                    .newBuilder()
                    .setClientId("headless-player")
                    .setPlayerName("Headless Player")
                    .build()
                    .toByteString(),
        )

    private fun connectRequest(): ClientToMatchServiceMessage {
        val gre =
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.ConnectReq_097b)
                .setConnectReq(ConnectReq.newBuilder())
                .build()
        return serviceMessage(
            type = ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
            requestId = 2,
            payload =
                ClientToMatchDoorConnectRequest
                    .newBuilder()
                    .setMatchId(matchId)
                    .setClientToGreMessageBytes(gre.toByteString())
                    .build()
                    .toByteString(),
        )
    }

    companion object {
        /** Create a deterministic match from a repository-local puzzle file. */
        fun puzzle(
            path: Path,
            matchId: String = "headless-match",
            seed: Long = 42L,
        ): HeadlessMatch {
            val puzzlePath = path.toAbsolutePath().normalize()
            require(Files.isRegularFile(puzzlePath)) { "Puzzle not found: $puzzlePath" }

            GameBootstrap.initializeCardDatabase(quiet = true)
            val runtimeConfigs =
                RuntimeMatchConfigRegistry().apply {
                    put(RuntimeMatchConfig(matchId = matchId, puzzle = puzzlePath.toString()))
                }
            val output = QueueingMatchOutput()
            val registry = MatchRegistry()
            val connection =
                MatchConnection(
                    registry = registry,
                    output = output,
                    matchConfig =
                        MatchConfig(
                            ai = AiConfig(speed = 0.0),
                            game = GameConfig(seed = seed),
                            server =
                                ServerConfig(
                                    bridgeTimeoutMs = 15_000L,
                                    promptFailsafeMs = 15_000L,
                                    aiTurnWaitMs = 2_000L,
                                    mulliganWaitMs = 2_000L,
                                ),
                        ),
                    cardRepository = AutoMappingCardRepository(useFixtures = true),
                    runtimeMatchConfigs = runtimeConfigs,
                    deferGameplayAdvance = false,
                )
            return HeadlessMatch(HeadlessMatchDriver(matchId, output, connection, registry))
        }

        private fun serviceMessage(
            type: ClientToMatchServiceMessageType,
            requestId: Int,
            payload: com.google.protobuf.ByteString,
        ): ClientToMatchServiceMessage =
            ClientToMatchServiceMessage
                .newBuilder()
                .setRequestId(requestId)
                .setClientToMatchServiceMessageType(type)
                .setPayload(payload)
                .build()
    }
}

private class HeadlessMatchDriver(
    val matchId: String,
    val output: QueueingMatchOutput,
    val connection: MatchConnection,
    val registry: MatchRegistry,
)

private class QueueingMatchOutput : MatchOutput {
    private val messages = ConcurrentLinkedQueue<MatchServiceToClientMessage>()

    override fun send(message: MatchServiceToClientMessage) {
        messages.add(message)
    }

    override fun close() {
        messages.clear()
    }

    fun drain(): List<MatchServiceToClientMessage> = generateSequence(messages::poll).toList()
}
