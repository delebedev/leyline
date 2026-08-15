package leyline.game.bundle

import leyline.game.state.ProjectionTransition

/** Neutral prepared cut shared by the Select and GatherCounters materializers. */
internal data class PreparedPayCostsCut(
    val bundle: BundleBuilder.BundleResult,
    val transition: ProjectionTransition,
    val closesPlaybackFrame: Boolean,
)
