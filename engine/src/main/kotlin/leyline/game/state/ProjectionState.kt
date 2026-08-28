package leyline.game.state

import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.LogicalSequenceState
import leyline.game.snapshot.GsmSnapshot

/**
 * Complete protocol history for one match projection.
 *
 * Forge owns rules state. This value owns only history created by projection:
 * client identities, lifecycle journals, prior visible state, and cursors.
 * A compile attempt edits a private [Editor] and returns its frozen value.
 */
data class ProjectionState(
    val revision: Long = 0,
    val identities: InstanceIdRegistry.State = InstanceIdRegistry.initialState(),
    val effects: SyntheticEffectProjection = SyntheticEffectProjection.initial(),
    val revealProxies: RevealProxyTracker.State = RevealProxyTracker.State(emptyMap()),
    val opponentKnowledge: OpponentKnowledgeTracker.State = OpponentKnowledgeTracker.State(emptyMap()),
    val annotations: AnnotationProjectionState = AnnotationProjectionState(),
    val limboInstanceIds: Set<Int> = emptySet(),
    val protoZones: Map<Int, Int> = emptyMap(),
    val persistentAnnotations: PersistentAnnotationState = PersistentAnnotationState.INITIAL,
    val delayedTriggerHolders: Map<Int, HolderRecord> = emptyMap(),
    val transientLinkedFaceFamilyIds: Set<InstanceId> = emptySet(),
    val tokenGrpIds: Map<Int, Int> = emptyMap(),
    val viewerCursors: Map<Int, ViewerProjectionCursor> = emptyMap(),
    val sequence: LogicalSequenceState = LogicalSequenceState(),
) {
    fun editor(): Editor = Editor(this)

    class Editor internal constructor(
        private val prior: ProjectionState,
    ) {
        val identities = InstanceIdRegistry.Planner(prior.identities)
        val effects = SyntheticEffectProjection.Planner(prior.effects)
        val revealProxies = RevealProxyTracker.Planner(prior.revealProxies)
        val annotations = AnnotationProjectionState.Planner(prior.annotations)

        var opponentKnowledge: OpponentKnowledgeTracker.State = prior.opponentKnowledge
        val limboInstanceIds = prior.limboInstanceIds.toMutableSet()
        val protoZones = prior.protoZones.toMutableMap()
        var persistentAnnotations: PersistentAnnotationState = prior.persistentAnnotations
        val delayedTriggerHolders = prior.delayedTriggerHolders.toMutableMap()
        var transientLinkedFaceFamilyIds = prior.transientLinkedFaceFamilyIds
        val tokenGrpIds = prior.tokenGrpIds.toMutableMap()
        val viewerCursors = prior.viewerCursors.toMutableMap()
        val sequence = LogicalSequencePlanner(prior.sequence)

        fun freeze(): ProjectionState =
            ProjectionState(
                revision = prior.revision + 1,
                identities = identities.freeze(),
                effects = effects.freeze(),
                revealProxies = revealProxies.freeze(),
                opponentKnowledge = opponentKnowledge,
                annotations = annotations.freeze(),
                limboInstanceIds = limboInstanceIds.toSet(),
                protoZones = protoZones.toMap(),
                persistentAnnotations =
                    persistentAnnotations.copy(
                        activeAnnotations = persistentAnnotations.activeAnnotations.toMap(),
                    ),
                delayedTriggerHolders = delayedTriggerHolders.toMap(),
                transientLinkedFaceFamilyIds = transientLinkedFaceFamilyIds.toSet(),
                tokenGrpIds = tokenGrpIds.toMap(),
                viewerCursors = viewerCursors.toMap(),
                sequence = sequence.snapshot(),
            )
    }

    companion object {
        fun initial(
            startInstanceId: Int = 100,
            sequence: LogicalSequenceState = LogicalSequenceState(),
        ): ProjectionState =
            ProjectionState(
                identities = InstanceIdRegistry.initialState(startInstanceId),
                sequence = sequence,
            )
    }
}

/** Viewer-specific diff baseline and one-shot submitted-target annotation state. */
data class ViewerProjectionCursor(
    val previousSnapshot: GsmSnapshot? = null,
    val pendingSubmittedTargets: PendingSubmittedTargets? = null,
)

data class PendingSubmittedTargets(
    val spellInstanceId: InstanceId,
    val casterSeatId: SeatId,
    val version: Long,
)

/** Shell work acknowledged only after the matching state transition installs. */
data class ProjectionAcknowledgements(
    val consumedEarthbendResolutionVersions: Set<Long> = emptySet(),
    val promptFacts: PromptFactConsumption = PromptFactConsumption(),
)

/** One tentative projection result. Installing it is one compare-and-set operation. */
data class ProjectionTransition(
    val expectedRevision: Long,
    val nextState: ProjectionState,
    val acknowledgements: ProjectionAcknowledgements = ProjectionAcknowledgements(),
)

class StaleProjectionTransitionException : IllegalStateException("Projection transition is stale; retry from the immutable frame input")
