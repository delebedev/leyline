package leyline.web

import com.google.protobuf.InvalidProtocolBufferException
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.config.RuntimeMatchLaunchResponse
import leyline.domain.PlayerId
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import leyline.match.MatchRegistry
import leyline.match.MatchResultObservation
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

private val relayLog = LoggerFactory.getLogger("leyline.web.WebGreRelay")

interface WebGreRelay {
    suspend fun attach(
        matchId: String,
        playerId: PlayerId?,
        session: DefaultWebSocketServerSession,
    ): Boolean
}

data class WebMatchLaunch(
    val config: RuntimeMatchConfig,
    val ownerPlayerId: PlayerId? = null,
    val publicAccess: Boolean = false,
)

class WebMatchHandle internal constructor(
    val response: RuntimeMatchLaunchResponse,
    val result: CompletionStage<MatchResultObservation>,
    private val closeMatch: suspend () -> Unit,
) {
    suspend fun close() = closeMatch()
}

interface WebGreEngineSession {
    /**
     * Feed one inbound browser frame to the engine. Outbound frames the
     * engine produces while processing this — on the caller's thread or on a
     * background engine thread (AI-turn playback, auto-advance) — are pushed
     * to the `onFrame` sink supplied at construction as they're written, not
     * collected and returned once this call unblocks.
     */
    fun receiveFromBrowser(payload: ByteArray)

    fun close()
}

/**
 * Owns the complete in-process lifecycle of web-launched matches and their browser relay.
 * Callers provide immutable launch values and observe committed results; engine registries,
 * connections, puzzle loading, delivery, and teardown stay inside this module.
 */
class InProcessWebGreRelay(
    private val engineSettings: EngineSettings,
    private val coordinator: MatchCoordinator,
    private val cardRepository: CardRepository,
    private val puzzlesDir: File,
    /**
     * How long a match engine survives with no attached browsers. A page
     * reload or transient socket drop detaches for a moment — closing the
     * engine immediately would destroy the match before the browser can
     * re-attach and resync.
     */
    private val idleCloseGraceMs: Long = 60_000L,
) : WebGreRelay {
    private val sessions = ConcurrentHashMap<String, RelaySession>()
    private val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
    private val launchOwners = ConcurrentHashMap<String, Any>()
    private val puzzleLibrary = PuzzleLibrary(puzzlesDir)

    fun launch(launch: WebMatchLaunch): WebMatchHandle {
        val response = runtimeMatchConfigs.configure(launch.config)
        val matchId = response.matchId
        val owner = Any()
        launchOwners[matchId] = owner
        val result = CompletableFuture<MatchResultObservation>()
        register(
            matchId = matchId,
            ownerPlayerId = launch.ownerPlayerId,
            publicAccess = launch.publicAccess,
            onClose = {
                if (launchOwners.remove(matchId, owner)) runtimeMatchConfigs.remove(matchId)
            },
        ) { onFrame, onClosed ->
            DirectWebGreEngineSession(
                engineSettings = engineSettings,
                coordinator = coordinator,
                cardRepository = cardRepository,
                runtimeMatchConfigs = runtimeMatchConfigs,
                onFrame = onFrame,
                onClosed = onClosed,
                puzzleLibrary = puzzleLibrary,
                resultObserver = { result.complete(it) },
            )
        }
        return WebMatchHandle(response, result) { close(matchId, owner) }
    }

    internal fun register(
        matchId: String,
        ownerPlayerId: PlayerId? = null,
        publicAccess: Boolean = false,
        onClose: () -> Unit = {},
        engineFactory: (onFrame: (ByteArray) -> Unit, onClosed: () -> Unit) -> WebGreEngineSession,
    ) {
        sessions.computeIfAbsent(matchId) { RelaySession() }.configure(engineFactory, ownerPlayerId, publicAccess, onClose)
    }

    override suspend fun attach(
        matchId: String,
        playerId: PlayerId?,
        session: DefaultWebSocketServerSession,
    ): Boolean {
        val relaySession = sessions[matchId] ?: return false
        val canDrive = relaySession.canDrive(playerId)
        if (!relaySession.canAttach(playerId)) return false
        relaySession.attach(session, canDrive)
        relaySession.scheduleIdleClose(idleCloseGraceMs) { sessions.remove(matchId, relaySession) }
        return true
    }

    private suspend fun close(
        matchId: String,
        owner: Any,
    ) {
        if (!launchOwners.remove(matchId, owner)) return
        runtimeMatchConfigs.remove(matchId)
        val relaySession = sessions.remove(matchId) ?: return
        relaySession.close()
    }

    /**
     * One match's relay state: attached browsers, the live engine, and the
     * streaming pump between them.
     *
     * The pump is a single long-lived coroutine draining [outbound] — a
     * frame the engine hands to its sink (from any thread) lands in this
     * channel and reaches attached browsers on the pump's own schedule,
     * independent of whatever browser-driven call is currently blocked
     * inside [WebGreEngineSession.receiveFromBrowser]. That decoupling is
     * the whole point: a multi-second engine call (AI turn, puzzle
     * auto-pass) no longer holds every frame it produces hostage until it
     * returns.
     */
    private class RelaySession(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        private val lock = Mutex()
        private val engineLock = Mutex()
        private val browsers = mutableMapOf<DefaultWebSocketServerSession, Boolean>()
        private val outbound = Channel<ByteArray>(Channel.UNLIMITED)
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)

        @Volatile var engine: WebGreEngineSession? = null

        @Volatile private var ownerPlayerId: PlayerId? = null

        @Volatile private var publicAccess: Boolean = false

        @Volatile private var closed: Boolean = false

        @Volatile private var onClose: () -> Unit = {}

        init {
            scope.launch {
                for (frame in outbound) {
                    val targets = lock.withLock { browsers.keys.toList() }
                    targets.forEach { target -> runCatching { target.send(Frame.Binary(fin = true, data = frame)) } }
                }
            }
        }

        fun configure(
            engineFactory: (onFrame: (ByteArray) -> Unit, onClosed: () -> Unit) -> WebGreEngineSession,
            ownerPlayerId: PlayerId?,
            publicAccess: Boolean,
            onClose: () -> Unit,
        ) {
            this.engine?.close()
            this.engine =
                engineFactory(
                    { bytes -> outbound.trySend(bytes) },
                    { scope.launch { disconnectBrowsers() } },
                )
            this.ownerPlayerId = ownerPlayerId
            this.publicAccess = publicAccess
            this.onClose = onClose
            closed = false
        }

        fun canAttach(playerId: PlayerId?): Boolean = publicAccess || (ownerPlayerId != null && ownerPlayerId == playerId)

        fun canDrive(playerId: PlayerId?): Boolean = ownerPlayerId != null && ownerPlayerId == playerId

        suspend fun attach(
            session: DefaultWebSocketServerSession,
            canDrive: Boolean,
        ) {
            val accepted =
                lock.withLock {
                    if (closed) false else browsers.put(session, canDrive) == null
                }
            if (!accepted) return
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        // Read-only viewers still need Auth + Connect to reach the
                        // engine — the connect handshake is what makes it emit the
                        // spectator bundle. Only drivers get game messages through.
                        is Frame.Binary -> {
                            val payload = frame.readBytes()
                            if (canDrive || isHandshake(payload)) dispatchToEngine(payload)
                        }
                        is Frame.Close -> break
                        is Frame.Text -> Unit
                        is Frame.Ping -> Unit
                        is Frame.Pong -> Unit
                    }
                }
            } finally {
                lock.withLock { browsers.remove(session) }
            }
        }

        private fun isHandshake(payload: ByteArray): Boolean {
            val type =
                try {
                    ClientToMatchServiceMessage.parseFrom(payload).clientToMatchServiceMessageType
                } catch (_: InvalidProtocolBufferException) {
                    return false
                }
            return type == ClientToMatchServiceMessageType.AuthenticateRequest_f487 ||
                type == ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487
        }

        /** After [graceMs] with no attached browsers, close the engine and run [onRemoved]. */
        fun scheduleIdleClose(
            graceMs: Long,
            onRemoved: () -> Unit,
        ) {
            scope.launch {
                if (graceMs > 0) delay(graceMs)
                if (closeIfIdle()) onRemoved()
            }
        }

        suspend fun closeIfIdle(): Boolean = close(requireIdle = true)

        suspend fun close(): Boolean = close(requireIdle = false)

        private suspend fun close(requireIdle: Boolean): Boolean {
            val closure =
                lock.withLock {
                    if (closed || (requireIdle && browsers.isNotEmpty())) return false
                    closed = true
                    val current = engine
                    engine = null
                    val attached = browsers.keys.toList()
                    browsers.clear()
                    Triple(current, attached, onClose)
                }
            closure.second.forEach { target ->
                runCatching { target.close(CloseReason(CloseReason.Codes.NORMAL, "match closed")) }
            }
            closure.first?.close()
            closure.third()
            scope.cancel()
            outbound.close()
            return true
        }

        /** Notify attached browsers that the engine is gone — e.g. it crashed mid-match. */
        private suspend fun disconnectBrowsers() {
            val targets = lock.withLock { browsers.keys.toList() }
            targets.forEach { target ->
                runCatching { target.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "match engine closed")) }
            }
        }

        private suspend fun dispatchToEngine(payload: ByteArray) {
            engineLock.withLock { engine?.receiveFromBrowser(payload) }
        }
    }
}

class DirectWebGreEngineSession(
    private val engineSettings: EngineSettings,
    private val coordinator: MatchCoordinator?,
    private val cardRepository: CardRepository,
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry,
    onFrame: (ByteArray) -> Unit,
    onClosed: () -> Unit = {},
    private val puzzleLibrary: PuzzleLibrary,
    private val resultObserver: (MatchResultObservation) -> Unit = {},
) : WebGreEngineSession {
    /**
     * Shared by the browser's seat and the [WebFamiliarSeat] the server drives
     * alongside it — the handshake reaches across seats through this registry
     * (seat 2's starting-player answer deals seat 1 its hand), so two registries
     * would leave each seat talking to itself.
     */
    private val registry = MatchRegistry()

    private fun openConnection(output: MatchOutput) =
        MatchConnection(
            registry = registry,
            output = output,
            engineSettings = engineSettings,
            puzzleLibrary = puzzleLibrary,
            coordinator = coordinator,
            cardRepository = cardRepository,
            runtimeMatchConfigs = runtimeMatchConfigs,
            resultObserver = resultObserver,
        )

    private val connection =
        openConnection(
            object : MatchOutput {
                override fun send(message: MatchServiceToClientMessage) {
                    onFrame(message.toByteArray())
                }

                override fun close() = onClosed()
            },
        )

    private val familiar = WebFamiliarSeat(::openConnection, ::needsFamiliarSeat)

    private fun needsFamiliarSeat(matchId: String): Boolean {
        val config = runtimeMatchConfigs.get(matchId)
        val puzzle = !config?.puzzle.isNullOrBlank() || config?.puzzleDefinition != null
        val spectating = config?.spectatorMode ?: engineSettings.spectatorMode
        return !puzzle && !spectating
    }

    init {
        connection.opened()
    }

    override fun receiveFromBrowser(payload: ByteArray) {
        val inbound =
            try {
                ClientToMatchServiceMessage.parseFrom(payload)
            } catch (_: InvalidProtocolBufferException) {
                return
            }
        runCatching {
            connection.receive(inbound)
            familiar.followBrowser(inbound)
        }.onFailure { error ->
            relayLog.error("GRE engine error while handling client message", error)
            connection.failed(error)
        }
    }

    override fun close() {
        connection.disconnected()
        familiar.close()
    }
}
