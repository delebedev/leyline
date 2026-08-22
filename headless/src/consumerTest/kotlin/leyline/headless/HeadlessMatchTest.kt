package leyline.headless

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStage
import wotc.mtgo.gre.external.messaging.Messages.MatchGameRoomStateType
import wotc.mtgo.gre.external.messaging.Messages.MatchState
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import java.nio.file.Path

class HeadlessMatchTest :
    FunSpec({
        fun response(
            prompt: GREToClientMessage,
            action: Action,
        ): ClientToGREMessage =
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.PerformActionResp_097b)
                .setGameStateId(prompt.gameStateId)
                .setRespId(prompt.msgId)
                .setPerformActionResp(PerformActionResp.newBuilder().addActions(action))
                .build()

        test("connect and pass return the next client-visible state") {
            HeadlessMatch.puzzle(Path.of("puzzles/bolt-face.pzl")).use { match ->
                val initial = match.connect()
                val afterPass = match.pass()

                assertSoftly {
                    initial.map { it.type }.take(3) shouldBe
                        listOf(
                            GREMessageType.ConnectResp_695e,
                            GREMessageType.GameStateMessage_695e,
                            GREMessageType.ActionsAvailableReq_695e,
                        )
                    afterPass.map { it.type } shouldContain GREMessageType.GameStateMessage_695e
                    match.client.pendingActions shouldNotBe null
                    match.client.life(1) shouldBe 20
                }
            }
        }

        test("play land is observed through the emitted zone transfer") {
            HeadlessMatch.puzzle(Path.of("puzzles/warmup-land-permanent.pzl")).use { match ->
                val initial = match.connect()
                val prompt = initial.single { it.hasActionsAvailableReq() }
                val playLand = prompt.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Play_add3 }
                val afterPlay = match.submit(response(prompt, playLand))
                val gameStates = afterPlay.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
                val transferState =
                    gameStates
                        .firstOrNull { state ->
                            state.annotationsList.any { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                        }
                val objectIdChanged =
                    transferState
                        ?.annotationsList
                        ?.firstOrNull { it.typeList.contains(AnnotationType.ObjectIdChanged) }
                val originalId = objectIdChanged?.detailInt(DetailKeys.ORIG_ID)
                val newId = objectIdChanged?.detailInt(DetailKeys.NEW_ID)
                val hand = transferState?.zonesList?.firstOrNull { it.type == ZoneType.Hand && it.ownerSeatId == 1 }
                val battlefield = transferState?.zonesList?.firstOrNull { it.type == ZoneType.Battlefield }
                val playedLand = transferState?.gameObjectsList?.firstOrNull { it.instanceId == newId }

                assertSoftly {
                    gameStates.shouldNotBeEmpty()
                    transferState shouldNotBe null
                    originalId shouldBe playLand.instanceId
                    hand shouldNotBe null
                    hand?.objectInstanceIdsCount shouldBe 1
                    hand?.objectInstanceIdsList?.contains(playLand.instanceId) shouldBe false
                    battlefield shouldNotBe null
                    battlefield?.objectInstanceIdsList?.contains(newId) shouldBe true
                    playedLand shouldNotBe null
                    match.client.zone(ZoneIds.P1_HAND)?.objectInstanceIdsCount shouldBe 1
                    match.client.objectsInZone(ZoneIds.BATTLEFIELD).any { it.instanceId == newId } shouldBe true
                }
            }
        }

        test("consumer-visible protocol constants and callable API stay engine-free") {
            val publicTypes =
                listOf(
                    HeadlessMatch::class.java,
                    HeadlessClient::class.java,
                    HeadlessEngine::class.java,
                    HeadlessCard::class.java,
                    HeadlessZone::class.java,
                )
            val signatureTypes =
                publicTypes.flatMap { type ->
                    val methods =
                        type.declaredMethods
                            .filter {
                                java.lang.reflect.Modifier
                                    .isPublic(it.modifiers)
                            }.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                    val constructors =
                        type.declaredConstructors
                            .filter {
                                java.lang.reflect.Modifier
                                    .isPublic(it.modifiers)
                            }.flatMap { it.parameterTypes.toList() }
                    methods + constructors
                }

            assertSoftly {
                ZoneIds.BATTLEFIELD shouldBe 28
                PromptIds.DECLARE_ATTACKERS shouldBe 6
                KeywordAbilityIds.HASTE shouldBe 9
                AnnotationConstants.BATTLEFIELD_ZONE_AFFECTOR shouldBe ZoneIds.BATTLEFIELD
                signatureTypes
                    .map(Class<*>::getName)
                    .none { it.startsWith("leyline.match.") || it.startsWith("leyline.game.state.") || it.startsWith("forge.") } shouldBe
                    true
            }
        }

        test("engine observation and fixture commands expose headless values") {
            HeadlessMatch.puzzle(Path.of("puzzles/bolt-face.pzl")).use { match ->
                match.connect()
                match.engine.addFixtureCard(1, HeadlessZone.Battlefield, "Grizzly Bears", tapped = true)
                match.engine.setSVar(1, HeadlessZone.Battlefield, "Grizzly Bears", "Probe", "present")
                match.engine.setCounter(1, HeadlessZone.Battlefield, "Grizzly Bears", "P1P1", 2)

                val bear = checkNotNull(match.engine.card(1, HeadlessZone.Battlefield, "Grizzly Bears"))

                assertSoftly {
                    bear.name shouldBe "Grizzly Bears"
                    bear.stateName shouldNotBe null
                    bear.counters.values shouldContain 2
                    bear.sVars["Probe"] shouldBe "present"
                    match.engine.libraryNames(1).shouldNotBeEmpty()
                }
            }
        }

        test("concede exposes the client completion sequence") {
            HeadlessMatch.puzzle(Path.of("puzzles/bolt-face.pzl")).use { match ->
                match.connect()
                val completion = match.concede()
                val states = completion.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
                val intermission = completion.single { it.hasIntermissionReq() }.intermissionReq
                val room =
                    match.client.serviceMessages.single {
                        it.hasMatchGameRoomStateChangedEvent() &&
                            it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType == MatchGameRoomStateType.MatchCompleted
                    }

                assertSoftly {
                    (states.size >= 3) shouldBe true
                    states.first().gameInfo.stage shouldBe GameStage.GameOver
                    states.first().gameInfo.matchState shouldBe MatchState.GameComplete
                    intermission.result.reason shouldBe ResultReason.Concede
                    intermission.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
                    room.matchGameRoomStateChangedEvent.gameRoomInfo.stateType shouldBe MatchGameRoomStateType.MatchCompleted
                }
            }
        }
    })

private fun wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo.detailInt(key: String): Int? =
    detailsList.firstOrNull { it.key == key }?.valueInt32List?.firstOrNull()
