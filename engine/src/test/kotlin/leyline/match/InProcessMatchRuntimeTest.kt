package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchConfig
import leyline.domain.service.MatchCoordinator
import leyline.game.InMemoryCardRepository
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class InProcessMatchRuntimeTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("launches, processes serialized GRE, publishes frames and observes one result") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val runtime = runtime()
            val handle =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("runtime", puzzleDefinition = PuzzleDefinition("runtime", lifecyclePuzzle)),
                        frames::add,
                    ),
                )

            handle.receive(auth("player"))
            handle.receive(connect("runtime"))
            handle.receive(concede())

            val types =
                frames
                    .map(MatchServiceToClientMessage::parseFrom)
                    .flatMap { it.greToClientEvent.greToClientMessagesList }
                    .map { it.type }
            assertSoftly {
                handle.response.matchId shouldBe "runtime"
                types shouldContain GREMessageType.GameStateMessage_695e
                handle.result
                    .toCompletableFuture()
                    .get()
                    .matchId shouldBe "runtime"
                handle.result
                    .toCompletableFuture()
                    .get()
                    .won shouldBe false
                handle.copilotProposal().reason shouldBe "match runtime handle is terminal"
            }
            handle.close()
        }

        test("advises the owned pending prompt without advancing the match") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val handle =
                runtime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("advice", puzzleDefinition = PuzzleDefinition("advice", advicePuzzle)),
                        frames::add,
                    ),
                )

            handle.receive(auth("player"))
            handle.receive(connect("advice"))
            val before = frames.toList()
            val prompt = before.greMessages().last { it.type == GREMessageType.ActionsAvailableReq_695e }

            val proposal = handle.copilotProposal()

            assertSoftly {
                proposal.intent shouldBe "pass"
                proposal.card shouldBe null
                proposal.promptKey shouldBe "${prompt.gameStateId}:${prompt.msgId}"
                proposal.gameStateId shouldBe prompt.gameStateId
                proposal.respId shouldBe prompt.msgId
                frames.toList() shouldBe before
                handle.result.toCompletableFuture().isDone shouldBe false
            }
            handle.close()
        }

        test("advice remains confined to each launched handle") {
            val runtime = runtime()
            val connectedHandle =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("connected-advice", puzzleDefinition = PuzzleDefinition("connected-advice", advicePuzzle)),
                        onFrame = {},
                    ),
                )
            val disconnectedHandle =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig(
                            "disconnected-advice",
                            puzzleDefinition = PuzzleDefinition("disconnected-advice", lifecyclePuzzle),
                        ),
                        onFrame = {},
                    ),
                )

            connectedHandle.receive(auth("connected-player"))
            connectedHandle.receive(connect("connected-advice"))

            assertSoftly {
                connectedHandle.copilotProposal().intent shouldBe "pass"
                disconnectedHandle.copilotProposal().intent shouldBe "unrealizable"
                disconnectedHandle.copilotProposal().reason shouldContain "not connected"
            }
            connectedHandle.close()
            disconnectedHandle.close()
        }

        test("advice reports not connected and closed handles explicitly") {
            val handle =
                runtime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("unavailable", puzzleDefinition = PuzzleDefinition("unavailable", advicePuzzle)),
                        onFrame = {},
                    ),
                )

            val notConnected = handle.copilotProposal()
            assertSoftly {
                notConnected.intent shouldBe "unrealizable"
                notConnected.reason shouldContain "not connected"
            }

            handle.close()

            val closed = handle.copilotProposal()
            assertSoftly {
                closed.intent shouldBe "unrealizable"
                closed.reason shouldContain "closed"
            }
        }

        test("reports engine failure and close is idempotent") {
            val closed = AtomicBoolean()
            val handle =
                runtime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("failure", puzzle = "missing"),
                        onFrame = {},
                        onClosed = { closed.set(true) },
                    ),
                )

            handle.receive(auth("player"))
            handle.receive(connect("failure"))
            handle.close()
            handle.close()

            closed.get() shouldBe true
        }

        test("public runtime seam has no Ktor types") {
            val types =
                listOf(MatchRuntime::class.java, MatchRuntimeHandle::class.java, MatchRuntimeLaunch::class.java)
                    .flatMap { type ->
                        type.methods.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                    }

            types.none { it.name.startsWith("io.ktor.") } shouldBe true
        }
    })

private fun runtime() =
    InProcessMatchRuntime(
        EngineSettings(seed = 42L, bridgeTimeoutMs = 2_000L, promptFailsafeMs = 2_000L),
        MatchCoordinator.NOOP,
        InMemoryCardRepository(),
        java.io.File("data/puzzles"),
    )

private fun auth(clientId: String): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
        .setPayload(
            AuthenticateRequest
                .newBuilder()
                .setClientId(clientId)
                .build()
                .toByteString(),
        ).build()
        .toByteArray()

private fun connect(matchId: String): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487)
        .setPayload(
            ClientToMatchDoorConnectRequest
                .newBuilder()
                .setMatchId(matchId)
                .setClientToGreMessageBytes(
                    ClientToGREMessage
                        .newBuilder()
                        .setSystemSeatId(1)
                        .setType(ClientMessageType.ConnectReq_097b)
                        .build()
                        .toByteString(),
                ).build()
                .toByteString(),
        ).build()
        .toByteArray()

private fun concede(): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToGremessage)
        .setPayload(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.ConcedeReq_097b)
                .build()
                .toByteString(),
        ).build()
        .toByteArray()

private fun List<ByteArray>.greMessages() =
    map(MatchServiceToClientMessage::parseFrom)
        .flatMap { it.greToClientEvent.greToClientMessagesList }

private val advicePuzzle =
    """
    [metadata]
    Name:Runtime advice
    Goal:Win
    Turns:2
    Difficulty:Easy
    Description:Play a land.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Forest
    humanlibrary=Forest
    ailibrary=Forest
    """.trimIndent()

private val lifecyclePuzzle =
    """
    [metadata]
    Name:Runtime lifecycle
    Goal:Win
    Turns:1
    Difficulty:Easy
    Description:Concede after initial publication.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Mountain
    humanlibrary=Mountain
    ailibrary=Mountain
    """.trimIndent()
