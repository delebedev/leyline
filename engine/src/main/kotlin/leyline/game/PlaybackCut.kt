package leyline.game

import leyline.bridge.handoff.BlockingInteraction
import leyline.game.bundle.BundleBuilder
import leyline.game.event.FrameEventLog
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal enum class PlaybackCutReason {
    LandPlayed,
    StackObjectCast,
    StackObjectResolved,
    ResolutionZoneCompleted,
    CountersChanged,
    PoisonChanged,
    TurnBegan,
    PhaseChanged,
    AttackersDeclared,
    BlockersDeclared,
    CombatEnded,
}

internal enum class PlaybackCutBoundary {
    MainLoopStep,
    AttackersDeclared,
    BlockersDeclared,
    CombatEnded,
}

internal data class PlaybackCutRequest(
    val reason: PlaybackCutReason,
    val delayMs: Int,
    val turnStarted: Boolean,
    val boundary: PlaybackCutBoundary = PlaybackCutBoundary.MainLoopStep,
) {
    fun aggregate(next: PlaybackCutRequest): PlaybackCutRequest =
        copy(
            delayMs = maxOf(delayMs, next.delayMs),
            turnStarted = turnStarted || next.turnStarted,
            boundary = if (next.boundary == PlaybackCutBoundary.MainLoopStep) boundary else next.boundary,
        )
}

internal data class PendingCut(
    val request: PlaybackCutRequest,
    val projection: BundleBuilder.PlaybackCut,
)

internal data class MaterializationDiagnostic(
    val request: PlaybackCutRequest,
    val events: FrameEventLog?,
)

/** Exact pre-publication interaction cut retained on terminal failure. */
internal data class PendingInteractionCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: BlockingInteraction,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition?,
)

/** Exact pre-publication Search cut retained on terminal failure. */
internal data class PendingSearchCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.SearchWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen Search input retained when materialization itself fails. */
internal data class SearchMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.SearchWindowValue,
)

/** Exact ordered-card cut retained on terminal failure. */
internal data class PendingOrderCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.OrderWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen ordered-card input retained when materialization itself fails. */
internal data class OrderMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.OrderWindowValue,
)

/** Exact Scry or Surveil cut retained on terminal failure. */
internal data class PendingGroupingCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.GroupingWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen Grouping input retained when materialization itself fails. */
internal data class GroupingMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.GroupingWindowValue,
)

/** Exact card-backed SelectN cut retained on terminal failure. */
internal data class PendingCardSelectCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.CardSelectWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen card-backed SelectN input retained when materialization itself fails. */
internal data class CardSelectMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.CardSelectWindowValue,
)

/** Exact reveal-backed SelectN cut retained on terminal failure. */
internal data class PendingRevealChoiceCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.RevealChoiceWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen reveal-backed SelectN input retained when materialization itself fails. */
internal data class RevealChoiceMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.RevealChoiceWindowValue,
)

/** Exact static enum SelectN cut retained on terminal failure. */
internal data class PendingStaticChoiceCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.StaticChoiceWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Exact modal CastingTimeOptionsReq cut retained on terminal failure. */
internal data class PendingModalChoiceCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.ModalChoiceWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen modal choice input retained when materialization itself fails. */
internal data class ModalChoiceMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.ModalChoiceWindowValue,
)

/** Frozen static enum SelectN input retained when materialization itself fails. */
internal data class StaticChoiceMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.StaticChoiceWindowValue,
)

/** Exact iterative mana-source payment cut retained on terminal failure. */
internal data class PendingManaSourcePaymentCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.ManaSourcePaymentWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen mana-source payment input retained when materialization itself fails. */
internal data class ManaSourcePaymentMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.ManaSourcePaymentWindowValue,
)

/** Exact one-shot PayCosts cut retained on terminal failure. */
internal data class PendingOneShotPayCostsCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.OneShotPayCostsWindow,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen one-shot PayCosts input retained when materialization itself fails. */
internal data class OneShotPayCostsMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.OneShotPayCostsWindow,
)

internal data class PromptTerminalFailureContext(
    val pendingSearchCut: PendingSearchCut? = null,
    val searchDiagnostic: SearchMaterializationDiagnostic? = null,
    val pendingOrderCut: PendingOrderCut? = null,
    val orderDiagnostic: OrderMaterializationDiagnostic? = null,
    val pendingGroupingCut: PendingGroupingCut? = null,
    val groupingDiagnostic: GroupingMaterializationDiagnostic? = null,
    val pendingCardSelectCut: PendingCardSelectCut? = null,
    val cardSelectDiagnostic: CardSelectMaterializationDiagnostic? = null,
    val pendingRevealChoiceCut: PendingRevealChoiceCut? = null,
    val revealChoiceDiagnostic: RevealChoiceMaterializationDiagnostic? = null,
    val pendingStaticChoiceCut: PendingStaticChoiceCut? = null,
    val staticChoiceDiagnostic: StaticChoiceMaterializationDiagnostic? = null,
    val pendingModalChoiceCut: PendingModalChoiceCut? = null,
    val modalChoiceDiagnostic: ModalChoiceMaterializationDiagnostic? = null,
    val pendingManaSourcePaymentCut: PendingManaSourcePaymentCut? = null,
    val manaSourcePaymentDiagnostic: ManaSourcePaymentMaterializationDiagnostic? = null,
    val pendingOneShotPayCostsCut: PendingOneShotPayCostsCut? = null,
    val oneShotPayCostsDiagnostic: OneShotPayCostsMaterializationDiagnostic? = null,
)

internal class PlaybackTerminalFailure(
    val pendingCut: PendingCut?,
    val diagnostic: MaterializationDiagnostic?,
    val pendingInteractionCut: PendingInteractionCut? = null,
    private val prompt: PromptTerminalFailureContext = PromptTerminalFailureContext(),
    cause: Throwable,
) : IllegalStateException("Playback projection terminated", cause) {
    val pendingSearchCut get() = prompt.pendingSearchCut
    val searchDiagnostic get() = prompt.searchDiagnostic
    val pendingOrderCut get() = prompt.pendingOrderCut
    val orderDiagnostic get() = prompt.orderDiagnostic
    val pendingGroupingCut get() = prompt.pendingGroupingCut
    val groupingDiagnostic get() = prompt.groupingDiagnostic
    val pendingCardSelectCut get() = prompt.pendingCardSelectCut
    val cardSelectDiagnostic get() = prompt.cardSelectDiagnostic
    val pendingRevealChoiceCut get() = prompt.pendingRevealChoiceCut
    val revealChoiceDiagnostic get() = prompt.revealChoiceDiagnostic
    val pendingStaticChoiceCut get() = prompt.pendingStaticChoiceCut
    val staticChoiceDiagnostic get() = prompt.staticChoiceDiagnostic
    val pendingModalChoiceCut get() = prompt.pendingModalChoiceCut
    val modalChoiceDiagnostic get() = prompt.modalChoiceDiagnostic
    val pendingManaSourcePaymentCut get() = prompt.pendingManaSourcePaymentCut
    val manaSourcePaymentDiagnostic get() = prompt.manaSourcePaymentDiagnostic
    val pendingOneShotPayCostsCut get() = prompt.pendingOneShotPayCostsCut
    val oneShotPayCostsDiagnostic get() = prompt.oneShotPayCostsDiagnostic
}
