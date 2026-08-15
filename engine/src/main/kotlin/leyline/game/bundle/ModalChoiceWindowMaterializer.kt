package leyline.game.bundle

import leyline.bridge.handoff.ModalChoiceWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Value-only GRE preparation for coordinator-owned modal CastingTimeOptionsReq windows. */
internal class ModalChoiceWindowMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
        val sourceInstanceId: Int,
    )

    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: ModalChoiceWindowValue,
    ): Prepared {
        val sourceCardInstanceId = projection.requireInstanceId(window.sourceForgeCardId)
        val sourceInstanceId =
            if (window.triggered) {
                projection.requireInstanceId(FrameIdResolver.triggerStackAbilityForgeId(window.sourceForgeAbilityId))
            } else {
                sourceCardInstanceId
            }
        val req =
            CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                parentGrpId = window.parentGrpId,
                modalOptions = window.possible.map { option -> CastingTimeOptionsBuilder.ModalOptionSpec(option.grpId, option.cost) },
                excludedOptions = window.excluded.map { option -> CastingTimeOptionsBuilder.ModalOptionSpec(option.grpId, option.cost) },
                minSel = window.min,
                maxSel = window.max,
                sourceInstanceId = sourceInstanceId,
                grpId = window.ctoGrpId,
                ctoId = window.ctoId,
                playerIdToPrompt = seatId,
            )
        val state =
            gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .let { builder ->
                    if (!window.triggered || builder.gameObjectsList.any { it.instanceId == sourceInstanceId }) {
                        builder
                    } else {
                        val ability =
                            GameObjectInfo
                                .newBuilder()
                                .setInstanceId(sourceInstanceId)
                                .setGrpId(window.ctoGrpId)
                                .setType(GameObjectType.Ability)
                                .setZoneId(ZoneIds.STACK)
                                .setVisibility(Visibility.Public)
                                .setOwnerSeatId(seatId)
                                .setControllerSeatId(seatId)
                                .setObjectSourceGrpId(window.sourceCardGrpId)
                                .setParentId(sourceCardInstanceId)
                                .build()
                        builder.addGameObjects(ability)
                        addToStack(builder, sourceInstanceId)
                    }
                }.build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) { it.gameStateMessage = state },
                makeGRE(GREMessageType.CastingTimeOptionsReq_695e, gameStateId, counter.nextMsgId()) {
                    it.castingTimeOptionsReq = req
                    it.prompt = Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build()
                    it.allowCancel = AllowCancel.Abort
                    it.allowUndo = true
                },
            )
        return Prepared(BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId), transition, true, sourceInstanceId)
    }

    fun cleanup(
        counter: MessageCounter,
        abilityInstanceId: Int,
    ): GREToClientMessage {
        val link = counter.nextGameStateLink()
        return makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
            it.gameStateMessage =
                GameStateMessage
                    .newBuilder()
                    .setType(wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(link.prevGsId)
                    .setUpdate(GameStateUpdate.Send)
                    .addDiffDeletedInstanceIds(abilityInstanceId)
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.STACK)
                            .setType(ZoneType.Stack)
                            .setVisibility(Visibility.Public)
                            .build(),
                    ).build()
        }
    }

    private fun addToStack(
        builder: GameStateMessage.Builder,
        instanceId: Int,
    ): GameStateMessage.Builder {
        val stackIndex = builder.zonesList.indexOfFirst { it.type == ZoneType.Stack }
        if (stackIndex >= 0) {
            builder.setZones(
                stackIndex,
                builder
                    .getZones(stackIndex)
                    .toBuilder()
                    .addObjectInstanceIds(instanceId)
                    .build(),
            )
        } else {
            builder.addZones(
                ZoneInfo
                    .newBuilder()
                    .setZoneId(ZoneIds.STACK)
                    .setType(ZoneType.Stack)
                    .setVisibility(Visibility.Public)
                    .addObjectInstanceIds(instanceId)
                    .build(),
            )
        }
        return builder
    }

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("ModalChoice source ${cardId.value} has no projected instance id")

    private fun makeGRE(
        type: GREMessageType,
        gameStateId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()
}
