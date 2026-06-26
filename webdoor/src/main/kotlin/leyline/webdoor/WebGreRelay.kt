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
    ) {
        sessions.computeIfAbsent(matchId) { RelaySession() }.configure(engine, ownerPlayerId, publicAccess)
    }

    override suspend fun attach(
        matchId: String,
        playerId: PlayerId?,
        session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    ): Boolean {
        val relaySession = sessions[matchId] ?: return false
        if (!relaySession.canAttach(playerId)) return false
        relaySession.attach(session)
        return true
    }

    private class RelaySession {
        private val lock = Mutex()
        private val browsers = mutableSetOf<io.ktor.server.websocket.DefaultWebSocketServerSession>()

        @Volatile var engine: WebGreEngineSession? = null

        @Volatile private var ownerPlayerId: PlayerId? = null

        @Volatile private var publicAccess: Boolean = false

        fun configure(
            engine: WebGreEngineSession,
            ownerPlayerId: PlayerId?,
            publicAccess: Boolean,
        ) {
            this.engine = engine
            this.ownerPlayerId = ownerPlayerId
            this.publicAccess = publicAccess
        }

        fun canAttach(playerId: PlayerId?): Boolean = publicAccess || (ownerPlayerId != null && ownerPlayerId == playerId)

        suspend fun attach(session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
            lock.withLock { browsers.add(session) }
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Binary -> dispatchToEngine(frame.readBytes())
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

        private suspend fun dispatchToEngine(payload: ByteArray) {
            val replies = engine?.receiveFromBrowser(payload).orEmpty()
            if (replies.isEmpty()) return
            val targets = lock.withLock { browsers.toList() }
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
