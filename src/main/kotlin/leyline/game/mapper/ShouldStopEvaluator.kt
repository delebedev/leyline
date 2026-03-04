package leyline.game.mapper

import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Determines whether the client should pause (stop the timer) after an action
 * is available. Real Arena: shouldStop=true on Cast/Play/Activate variants,
 * false on Pass/FloatMana/ActivateMana.
 */
object ShouldStopEvaluator {

    fun shouldStop(actionType: ActionType): Boolean = when (actionType) {
        ActionType.Cast,
        ActionType.CastLeft,
        ActionType.CastRight,
        ActionType.CastAdventure,
        ActionType.CastMdfc,
        ActionType.CastPrototype,
        ActionType.CastLeftRoom,
        ActionType.CastRightRoom,
        ActionType.CastOmen,
        ActionType.Play_add3,
        ActionType.PlayMdfc,
        ActionType.Activate_add3,
        -> true
        else -> false
    }
}
