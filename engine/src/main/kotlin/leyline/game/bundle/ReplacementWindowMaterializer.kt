package leyline.game.bundle

import leyline.bridge.handoff.ReplacementKeywordKind
import leyline.bridge.handoff.ReplacementWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActivatedActionEmitter
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementReq

/**
 * Value-only GRE preparation for coordinator-owned competing-replacement
 * windows. Resolves each retained self-replacement handle to a complete
 * [ReplacementEffect] row and wraps them in a `SelectReplacementReq` (type 39,
 * promptId 74, No cancel, undo=true). Unobserved fields stay omitted.
 */
internal class ReplacementWindowMaterializer(
    private val seatId: Int,
    private val cardRepository: CardRepository,
    private val grpIdResolver: (ForgeCardId) -> Int?,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
        val rows: List<ReplacementEffect>,
    )

    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: ReplacementWindowValue,
    ): Prepared {
        val rows = window.options.map { option -> row(option, projection) }
        require(rows.map { it.replacementEffectId }.distinct().size == rows.size) {
            "Replacement rows have colliding request-local effect ids"
        }
        val request = SelectReplacementReq.newBuilder().addAllReplacements(rows).build()
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = state
                },
                makeGRE(GREMessageType.SelectReplacementReq_695e, gameStateId, counter.nextMsgId()) {
                    it.selectReplacementReq = request
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(PromptIds.SELECT_REPLACEMENT)
                            .build()
                    it.allowCancel = AllowCancel.No_a526
                    it.allowUndo = true
                },
            )
        return Prepared(
            BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId),
            transition,
            closesPlaybackFrame = true,
            rows = rows,
        )
    }

    private fun row(
        option: leyline.bridge.handoff.ReplacementOptionValue,
        projection: ProjectionState,
    ): ReplacementEffect {
        val instanceId = projection.requireInstanceId(option.hostForgeCardId)
        val grpId = grpIdResolver(option.hostForgeCardId) ?: error("Replacement host has no card grpId")
        val abilityGrpId =
            when (option.keyword) {
                ReplacementKeywordKind.Madness ->
                    cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.MADNESS)
                        ?: error("Replacement host has no Madness ability grpId")
            }
        val uniqueAbilityId =
            ActivatedActionEmitter.uniqueAbilityIdFor(cardRepository.findByGrpId(grpId), abilityGrpId)
                ?: error("Replacement host ability $abilityGrpId has no unique ability id")
        return ReplacementEffect
            .newBuilder()
            .setObjectInstance(instanceId)
            .setUniqueAbilityId(uniqueAbilityId)
            .setAbilityGrpId(abilityGrpId)
            .setAffectedObject(instanceId)
            .setReplacementEffectId(replacementEffectIdFor(option.originalOptionIndex))
            .build()
    }

    private fun replacementEffectIdFor(optionIndex: Int): Int = SELECT_REPLACEMENT_EFFECT_ID_BASE + optionIndex

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value
            ?: error("Replacement host ${cardId.value} has no projected instance id")

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

    private companion object {
        /** Request-local base for replacementEffectId; stable within one window and echoed verbatim by the client. */
        const val SELECT_REPLACEMENT_EFFECT_ID_BASE = 9000
    }
}
