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
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.PlayerId
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import leyline.match.MatchRegistry
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val relayLog = LoggerFactory.getLogger("leyline.web.WebGreRelay")

interface WebGreRelay {
    /**
     * Register (or replace) the engine driving [matchId]. [engineFactory] is
     * called with a frame sink and a close signal instead of receiving a
     * pre-built [WebGreEngineSession] — the relay owns both so it can stream
     * frames to attached browsers as the engine writes them, not only after
     * a browser-driven call returns.
     */
    fun register(
        matchId: String,
        ownerPlayerId: PlayerId? = null,
        publicAccess: Boolean = false,
        onClose: () -> Unit = {},
        engineFactory: (onFrame: (ByteArray) -> Unit, onClosed: () -> Unit) -> WebGreEngineSession,
    )

    suspend fun attach(
        matchId: String,
        playerId: PlayerId?,
        session: DefaultWebSocketServerSession,
    ): Boolean
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

class InProcessWebGreRelay(
    /**
     * How long a match engine survives with no attached browsers. A page
     * reload or transient socket drop detaches for a moment — closing the
     * engine immediately would destroy the match before the browser can
     * re-attach and resync.
     */
    private val idleCloseGraceMs: Long = 60_000L,
) : WebGreRelay {
    private val sessions = ConcurrentHashMap<String, RelaySession>()

    override fun register(
        matchId: String,
        ownerPlayerId: PlayerId?,
        publicAccess: Boolean,
        onClose: () -> Unit,
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

        suspend fun closeIfIdle(): Boolean {
            val engineToClose =
                lock.withLock {
                    if (closed || browsers.isNotEmpty()) return false
                    closed = true
                    val current = engine ?: return false
                    engine = null
                    current
                }
            engineToClose.close()
            onClose()
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
    private val coordinator: MatchCoordinator,
    private val cardRepository: CardRepository,
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry,
    onFrame: (ByteArray) -> Unit,
    onClosed: () -> Unit = {},
    private val puzzlesDir: File,
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
            puzzlesDir = puzzlesDir,
            coordinator = coordinator,
            cardRepository = cardRepository,
            runtimeMatchConfigs = runtimeMatchConfigs,
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
        val puzzle = !config?.puzzle.isNullOrBlank()
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
