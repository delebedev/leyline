package leyline.match

import com.google.protobuf.ByteString
import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
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
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import java.io.File

/**
 * Diagnostic dump: what does the web-shaped [MatchHandler] send after a land
 * play resolves? Answers whether the follow-up [wotc.mtgo.gre.external.messaging.Messages.GameStateMessage]
 * carries an updated Hand zone (card removed) or only a Battlefield update,
 * whether it's Full or Diff, and whether the transfer is annotation-only.
 */
class PuzzleLandPlayGsmDumpTest :
    FunSpec({

        tags(IntegrationTag)

        val jsonPrinter = JsonFormat.printer().includingDefaultValueFields()

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        fun tempPuzzleFile(): File =
            File.createTempFile("leyline-puzzle-landplay-", ".pzl").apply {
                deleteOnExit()
                writeText(
                    """
                    [metadata]
                    Name:Land Play Dump
                    Goal:Win
                    Turns:1
                    Difficulty:Easy
                    Description:One land in hand, one on the battlefield, to observe a Play_add3 diff.

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=Mountain
                    humanbattlefield=Forest
                    humanlibrary=Mountain
                    ailibrary=Mountain
                    """.trimIndent(),
                )
            }

        fun serviceMessage(
            type: ClientToMatchServiceMessageType,
            payload: ByteString,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            ClientToMatchServiceMessage
                .newBuilder()
                .setRequestId(requestId)
                .setClientToMatchServiceMessageType(type)
                .setPayload(payload)
                .build()

        fun greMessage(
            seatId: Int,
            type: ClientMessageType,
            customize: ClientToGREMessage.Builder.() -> Unit = {},
        ): ClientToGREMessage =
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(seatId)
                .setType(type)
                .apply(customize)
                .build()

        fun auth(
            clientId: String,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.AuthenticateRequest_f487,
                AuthenticateRequest
                    .newBuilder()
                    .setClientId(clientId)
                    .setPlayerName(clientId)
                    .build()
                    .toByteString(),
                requestId,
            )

        fun connect(
            matchId: String,
            seatId: Int,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
                ClientToMatchDoorConnectRequest
                    .newBuilder()
                    .setMatchId(matchId)
                    .setClientToGreMessageBytes(
                        greMessage(seatId, ClientMessageType.ConnectReq_097b) {
                            setConnectReq(ConnectReq.newBuilder())
                        }.toByteString(),
                    ).build()
                    .toByteString(),
                requestId,
            )

        fun greServiceMessage(
            gre: ClientToGREMessage,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToGremessage,
                gre.toByteString(),
                requestId,
            )

        fun greOutbound(channel: EmbeddedChannel): List<GREToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }
                .filter { it.hasGreToClientEvent() }
                .flatMap { it.greToClientEvent.greToClientMessagesList }
                .toList()

        test("dump the GSM sent after a human land play resolves") {
            val registry = MatchRegistry()
            val runtimeMatchConfigs = RuntimeMatchConfigRegistry()
            val matchId = "web-puzzle-landplay-dump"
            val temp = tempPuzzleFile()

            try {
                runtimeMatchConfigs.put(
                    RuntimeMatchConfig(
                        matchId = matchId,
                        puzzleDefinition = PuzzleDefinition(temp.nameWithoutExtension, temp.readText()),
                    ),
                )
                val handler =
                    MatchHandler(
                        registry = registry,
                        engineSettings = EngineSettings(),
                        cardRepository = TestCardRegistry.repo,
                        runtimeMatchConfigs = runtimeMatchConfigs,
                    )
                val channel = EmbeddedChannel(handler)

                channel.writeInbound(auth("web-player", 1))
                greOutbound(channel)
                channel.writeInbound(connect(matchId, seatId = 1, requestId = 2))
                val connectMessages = greOutbound(channel)

                val initialGsm = connectMessages.first { it.hasGameStateMessage() }.gameStateMessage
                println("CONNECT MESSAGE TYPES: ${connectMessages.map { it.type }}")
                println("INITIAL GSM actions: ${initialGsm.actionsList.map { it.action.actionType to it.action.instanceId }}")
                println("INITIAL GSM:\n${jsonPrinter.print(initialGsm)}")

                val handZoneBefore = initialGsm.zonesList.first { it.type == ZoneType.Hand && it.ownerSeatId == 1 }
                // Battlefield is one shared zone (ownerSeatId=0) — objects carry their own owner/controller.
                val battlefieldBefore = initialGsm.zonesList.first { it.type == ZoneType.Battlefield }
                val playAction =
                    initialGsm.actionsList
                        .map { it.action }
                        .first { it.actionType == ActionType.Play_add3 }
                val mountainInstanceId = playAction.instanceId

                val performPlay =
                    greMessage(1, ClientMessageType.PerformActionResp_097b) {
                        val prompt = connectMessages.last { it.hasActionsAvailableReq() }
                        setGameStateId(prompt.gameStateId)
                        setRespId(prompt.msgId)
                        setPerformActionResp(
                            PerformActionResp
                                .newBuilder()
                                .addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(mountainInstanceId)),
                        )
                    }
                channel.writeInbound(greServiceMessage(performPlay, 3))
                val postPlayMessages = greOutbound(channel)
                val postPlayTypes = postPlayMessages.map { it.type }
                val gsmMessages = postPlayMessages.filter { it.hasGameStateMessage() }

                println("POST-PLAY MESSAGE TYPES: $postPlayTypes")
                for ((idx, m) in gsmMessages.withIndex()) {
                    println("POST-PLAY GSM[$idx] type=${m.gameStateMessage.type}:\n${jsonPrinter.print(m.gameStateMessage)}")
                }

                // The zone-transfer diff — the first GSM whose annotations name the
                // PlayLand transfer (subsequent diffs are unrelated phase/priority churn).
                val transferGsm =
                    gsmMessages
                        .map { it.gameStateMessage }
                        .first { gsm ->
                            gsm.annotationsList.any {
                                it.typeList.contains(AnnotationType.ZoneTransfer_af5a)
                            }
                        }
                val objectIdChanged =
                    transferGsm.annotationsList.first { it.typeList.contains(AnnotationType.ObjectIdChanged) }
                val origId =
                    objectIdChanged.detailsList
                        .first { it.key == "orig_id" }
                        .valueInt32List
                        .first()
                val newId =
                    objectIdChanged.detailsList
                        .first { it.key == "new_id" }
                        .valueInt32List
                        .first()

                val handZoneAfter = transferGsm.zonesList.firstOrNull { it.type == ZoneType.Hand && it.ownerSeatId == 1 }
                val battlefieldAfter = transferGsm.zonesList.firstOrNull { it.type == ZoneType.Battlefield }
                val newMountainObject = transferGsm.gameObjectsList.firstOrNull { it.instanceId == newId }
                val zoneTransferAnnotation =
                    transferGsm.annotationsList.first { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }

                val zoneTransferDetails = zoneTransferAnnotation.detailsList.map { it.key to (it.valueInt32List + it.valueStringList) }
                println(
                    "SUMMARY: postPlayTypes=$postPlayTypes " +
                        "handZoneBefore.objectInstanceIds=${handZoneBefore.objectInstanceIdsList} " +
                        "handZoneAfter(inTransferDiff)=${handZoneAfter?.objectInstanceIdsList} " +
                        "battlefieldBefore.objectInstanceIds=${battlefieldBefore.objectInstanceIdsList} " +
                        "battlefieldAfter(inTransferDiff)=${battlefieldAfter?.objectInstanceIdsList} " +
                        "origId=$origId newId=$newId newMountainObject.zoneId=${newMountainObject?.zoneId} " +
                        "zoneTransfer.affectedIds=${zoneTransferAnnotation.affectedIdsList} " +
                        "zoneTransfer.details=$zoneTransferDetails",
                )

                assertSoftly {
                    gsmMessages.shouldNotBeEmpty()
                    origId shouldBe mountainInstanceId
                    // The Hand zone the client should be diffing against IS present in this
                    // diff, and IS emptied — the server does send the shrunk hand.
                    handZoneAfter shouldNotBe null
                    handZoneAfter?.objectInstanceIdsList shouldBe emptyList()
                    battlefieldAfter?.objectInstanceIdsList?.contains(newId) shouldBe true
                    newMountainObject shouldNotBe null
                }
            } finally {
                temp.delete()
            }
        }
    })
