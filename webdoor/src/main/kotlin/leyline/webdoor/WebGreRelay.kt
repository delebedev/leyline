package leyline.webdoor

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

interface WebGreRelay {
    suspend fun attach(
        matchId: String,
        session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    )
}

class InProcessWebGreRelay : WebGreRelay {
    private val sessions = ConcurrentHashMap<String, RelaySession>()

    override suspend fun attach(
        matchId: String,
        session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    ) {
        sessions.computeIfAbsent(matchId) { RelaySession() }.attach(session)
    }

    private class RelaySession {
        private val lock = Mutex()
        private val retained = ArrayDeque<ByteArray>()
        private val browsers = mutableSetOf<io.ktor.server.websocket.DefaultWebSocketServerSession>()

        suspend fun attach(session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
            val replay =
                lock.withLock {
                    browsers.add(session)
                    retained.toList()
                }
            replay.forEach { session.send(Frame.Binary(fin = true, data = it)) }
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Binary -> dispatch(frame.readBytes())
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

        private suspend fun dispatch(payload: ByteArray) {
            val targets =
                lock.withLock {
                    retained.addLast(payload)
                    while (retained.size > 512) retained.removeFirst()
                    browsers.toList()
                }
            targets.forEach { target -> target.send(Frame.Binary(fin = true, data = payload)) }
        }
    }
}
