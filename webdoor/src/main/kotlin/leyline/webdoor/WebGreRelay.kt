package leyline.webdoor

import com.google.protobuf.InvalidProtocolBufferException
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.PlayerId
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.match.MatchHandler
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.util.concurrent.ConcurrentHashMap

interface WebGreRelay {
    fun register(
        matchId: String,
        engine: WebGreEngineSession,
        ownerPlayerId: PlayerId? = null,
        publicAccess: Boolean = false,
        onClose: () -> Unit = {},
    )

    suspend fun attach(
        matchId: String,
        playerId: PlayerId?,
        session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    ): Boolean
}

interface WebGreEngineSession {
    fun receiveFromBrowser(payload: ByteArray): List<ByteArray>

    fun close()
}

class InProcessWebGreRelay : WebGreRelay {
    private val sessions = ConcurrentHashMap<String, RelaySession>()

    override fun register(
        matchId: String,
        engine: WebGreEngineSession,
        ownerPlayerId: PlayerId?,
        publicAccess: Boolean,
        onClose: () -> Unit,
    ) {
        sessions.computeIfAbsent(matchId) { RelaySession() }.configure(engine, ownerPlayerId, publicAccess, onClose)
    }

    override suspend fun attach(
        matchId: String,
        playerId: PlayerId?,
        session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    ): Boolean {
        val relaySession = sessions[matchId] ?: return false
        val canDrive = relaySession.canDrive(playerId)
        if (!relaySession.canAttach(playerId)) return false
        relaySession.attach(session, canDrive)
        if (relaySession.closeIfIdle()) {
            sessions.remove(matchId, relaySession)
        }
        return true
    }

    private class RelaySession {
        private val lock = Mutex()
        private val engineLock = Mutex()
        private val browsers = mutableMapOf<io.ktor.server.websocket.DefaultWebSocketServerSession, Boolean>()

        @Volatile var engine: WebGreEngineSession? = null

        @Volatile private var ownerPlayerId: PlayerId? = null

        @Volatile private var publicAccess: Boolean = false

        @Volatile private var closed: Boolean = false

        @Volatile private var onClose: () -> Unit = {}

        fun configure(
            engine: WebGreEngineSession,
            ownerPlayerId: PlayerId?,
            publicAccess: Boolean,
            onClose: () -> Unit,
        ) {
            this.engine?.close()
            this.engine = engine
            this.ownerPlayerId = ownerPlayerId
            this.publicAccess = publicAccess
            this.onClose = onClose
            closed = false
        }

        fun canAttach(playerId: PlayerId?): Boolean = publicAccess || (ownerPlayerId != null && ownerPlayerId == playerId)

        fun canDrive(playerId: PlayerId?): Boolean = ownerPlayerId != null && ownerPlayerId == playerId

        suspend fun attach(
            session: io.ktor.server.websocket.DefaultWebSocketServerSession,
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
                        is Frame.Binary -> if (canDrive) dispatchToEngine(frame.readBytes())
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

        suspend fun closeIfIdle(): Boolean {
            val engineToClose =
                lock.withLock {
                    if (closed || browsers.isNotEmpty()) return false
                    closed = true
                    engine.also { engine = null }
                }
            engineToClose?.close()
            onClose()
            return true
        }

        private suspend fun dispatchToEngine(payload: ByteArray) {
            val replies = engineLock.withLock { engine?.receiveFromBrowser(payload).orEmpty() }
            if (replies.isEmpty()) return
            val targets = lock.withLock { browsers.keys.toList() }
            replies.forEach { reply -> targets.forEach { target -> target.send(Frame.Binary(fin = true, data = reply)) } }
        }
    }
}

class EmbeddedWebGreEngineSession(
    matchConfig: MatchConfig,
    coordinator: MatchCoordinator,
    cardRepository: CardRepository,
    runtimeMatchConfigs: RuntimeMatchConfigRegistry,
) : WebGreEngineSession {
    private val channel =
        EmbeddedChannel(
            MatchHandler(
                matchConfig = matchConfig,
                coordinator = coordinator,
                cardRepository = cardRepository,
                runtimeMatchConfigs = runtimeMatchConfigs,
            ),
        )

    init {
        channel.pipeline().fireChannelActive()
    }

    override fun receiveFromBrowser(payload: ByteArray): List<ByteArray> {
        val inbound =
            try {
                ClientToMatchServiceMessage.parseFrom(payload)
            } catch (_: InvalidProtocolBufferException) {
                return emptyList()
            }
        channel.writeInbound(inbound)
        channel.runPendingTasks()
        channel.runScheduledPendingTasks()

        val replies = mutableListOf<ByteArray>()
        while (true) {
            val outbound = channel.readOutbound<MatchServiceToClientMessage>() ?: break
            replies += outbound.toByteArray()
        }
        return replies
    }

    override fun close() {
        channel.close()
    }
}
