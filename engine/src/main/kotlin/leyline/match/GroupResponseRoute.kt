package leyline.match

import leyline.bridge.types.MulliganPhase

internal enum class GroupResponseRoute {
    Grouping,
    LondonTuck,
    Stale,
}

internal fun groupResponseRoute(
    groupingPending: Boolean,
    mulliganPhase: MulliganPhase?,
): GroupResponseRoute =
    when {
        groupingPending -> GroupResponseRoute.Grouping
        mulliganPhase == MulliganPhase.WaitingTuck -> GroupResponseRoute.LondonTuck
        else -> GroupResponseRoute.Stale
    }
