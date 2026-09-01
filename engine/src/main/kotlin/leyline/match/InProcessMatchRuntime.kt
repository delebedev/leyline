package leyline.match

import com.google.protobuf.InvalidProtocolBufferException
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.config.RuntimeMatchLaunchResponse
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.infra.MatchOutput
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

private val runtimeLog = LoggerFactory.getLogger("leyline.match.InProcessMatchRuntime")

data class MatchRuntimeLaunch(
    val config: RuntimeMatchConfig,
    val onFrame: (ByteArray) -> Unit,
    val onClosed: () -> Unit = {},
)

interface MatchRuntime {
    fun launch(launch: MatchRuntimeLaunch): MatchRuntimeHandle
}

interface MatchRuntimeHandle {
    val response: RuntimeMatchLaunchResponse
    val result: CompletionStage<MatchResultObservation>

    fun receive(payload: ByteArray)

    fun close()
}

/** Owns one in-process engine lifecycle per launched handle. */
class InProcessMatchRuntime(
    private val engineSettings: EngineSettings,
    private val coordinator: MatchCoordinator,
    private val cardRepository: CardRepository,
    puzzlesDir: File,
) : MatchRuntime {
    private val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
    private val launchOwners = ConcurrentHashMap<String, Any>()
    private val puzzleLibrary = PuzzleLibrary(puzzlesDir)

    override fun launch(launch: MatchRuntimeLaunch): MatchRuntimeHandle {
        val response = runtimeMatchConfigs.configure(launch.config)
        val owner = Any()
        launchOwners[response.matchId] = owner
        val result = CompletableFuture<MatchResultObservation>()
        return Handle(response, result, launch, owner)
    }

    private inner class Handle(
        override val response: RuntimeMatchLaunchResponse,
        override val result: CompletableFuture<MatchResultObservation>,
        private val launch: MatchRuntimeLaunch,
        private val owner: Any,
    ) : MatchRuntimeHandle {
        private val lock = Any()
        private val registry = MatchRegistry()
        private var closed = false

        private fun openConnection(output: MatchOutput) =
            MatchConnection(
                registry = registry,
                output = output,
                engineSettings = engineSettings,
                puzzleLibrary = puzzleLibrary,
                coordinator = coordinator,
                cardRepository = cardRepository,
                runtimeMatchConfigs = runtimeMatchConfigs,
                resultObserver = { result.complete(it) },
            )

        private val connection =
            openConnection(
                object : MatchOutput {
                    override fun send(message: MatchServiceToClientMessage) = launch.onFrame(message.toByteArray())

                    override fun close() = launch.onClosed()
                },
            )

        private val companionSeat = RuntimeCompanionSeat(::openConnection, ::needsCompanionSeat)

        init {
            connection.opened()
        }

        override fun receive(payload: ByteArray) {
            val inbound =
                try {
                    ClientToMatchServiceMessage.parseFrom(payload)
                } catch (_: InvalidProtocolBufferException) {
                    return
                }
            synchronized(lock) {
                if (closed) return
                runCatching {
                    connection.receive(inbound)
                    companionSeat.follow(inbound)
                }.onFailure { error ->
                    runtimeLog.error("GRE engine error while handling client message", error)
                    closed = true
                    connection.failed(error)
                    companionSeat.close()
                    removeConfig()
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                connection.disconnected()
                companionSeat.close()
                removeConfig()
            }
        }

        private fun needsCompanionSeat(matchId: String): Boolean {
            val config = runtimeMatchConfigs.get(matchId)
            val puzzle = !config?.puzzle.isNullOrBlank() || config?.puzzleDefinition != null
            val spectating = config?.spectatorMode ?: engineSettings.spectatorMode
            return !puzzle && !spectating
        }

        private fun removeConfig() {
            if (launchOwners.remove(response.matchId, owner)) runtimeMatchConfigs.remove(response.matchId)
        }
    }
}
