package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.handoff.StaticChoiceInteractionTimeoutException
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import java.util.concurrent.CompletableFuture

/** Exact static enum SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchStaticChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
) : StaticChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedStaticChoiceInteraction,
        val value: StaticChoiceWindowValue,
        override val cut: PendingPromptCut<StaticChoiceWindowValue>,
        val optionByValue: Map<Int, Int>,
        override val future: CompletableFuture<List<Int>> = CompletableFuture(),
    ) : SettledPromptOwner.Window<List<Int>> {
        override val interactionId: String get() = published.interactionId
    }

    private val slot =
        settled.mount<Window, List<Int>>(
            PromptTerminalPriority.StaticChoice,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message -> message.type == ClientMessageType.SelectNresp },
            admitLocked = ::admitLocked,
        )

    internal var beforeResponseComplete: (() -> Unit)? = null

    override fun awaitSelection(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int> {
        val initial =
            try {
                StaticChoiceWindowCapture.initial(request)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedStaticChoiceInteraction? = slot.current()?.published

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<List<Int>>? {
        val selectedValues = message.selectNResp.idsList
        if (selectedValues.size !in pending.value.min..pending.value.max) return null
        if (selectedValues.size != selectedValues.distinct().size) return null
        val options = selectedValues.map { pending.optionByValue[it] ?: return null }
        return SettledPromptOwner.SlotAdmission(
            options,
            beforeComplete = {
                recordChoiceResults(pending, selectedValues)
                beforeResponseComplete?.invoke()
            },
        )
    }

    private fun publish(initial: StaticChoiceWindowValue): Window =
        slot.publish(
            duplicateMessage = "A StaticChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial)
                val preparedViewers =
                    try {
                        feed.builder.prepareStaticChoiceWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial,
                            owner.viewerRoutes(),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published =
                    PublishedStaticChoiceInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        initial.kind,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val optionByValue = offeredOptions(initial, prepared.bundle.messages)
                val created = Window(published, initial, exact, optionByValue)
                SettledPromptOwner.Publication(
                    created,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    prepared.correlation,
                )
            },
        )

    private fun recordChoiceResults(
        pending: Window,
        selectedValues: List<Int>,
    ) {
        val domain = pending.value.staticList ?: return
        val source = pending.value.sourceForgeCardId ?: return
        // A type offered by label records its enum value; a type without one records nothing.
        selectedValues.mapNotNull { pending.value.options[pending.optionByValue.getValue(it)].protocolValue }.forEach { value ->
            owner.bridge
                .seat(owner.humanSeat)
                .prompt.journal
                .record(
                    PromptSideEffect.ChoiceResult(
                        sourceForgeCardId = source,
                        chooserSeatId = owner.humanSeat,
                        choiceValue = value,
                        choiceDomain = domain.number,
                        sentiment = 2,
                    ),
                )
        }
    }

    /**
     * Maps each value the published request offers to its option. Projection may offer a
     * type choice by enum value or, for types without one, by prompt index over every option.
     */
    private fun offeredOptions(
        initial: StaticChoiceWindowValue,
        messages: List<GREToClientMessage>,
    ): Map<Int, Int> {
        val request = messages.lastOrNull { it.hasSelectNReq() }?.selectNReq
        return if (request?.idType == IdType.PromptParameterIndex) {
            initial.options.associate { it.originalOptionIndex + 1 to it.originalOptionIndex }
        } else {
            initial.options.mapNotNull { option -> option.protocolValue?.let { it to option.originalOptionIndex } }.toMap()
        }
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): List<Int> = slot.await(pending, timeoutMs, ::StaticChoiceInteractionTimeoutException)
}
