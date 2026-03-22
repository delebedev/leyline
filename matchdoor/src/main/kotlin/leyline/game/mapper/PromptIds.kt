package leyline.game.mapper

/** Protocol prompt IDs matching real server values. */
object PromptIds {
    const val PASS_PRIORITY = 2
    const val DECLARE_ATTACKERS = 6
    const val ORDER_BLOCKERS = 7
    const val SELECT_TARGETS = 10
    const val PAY_COSTS = 11
    const val CASTING_TIME_OPTIONS = 23
    const val MATCH_RESULT_WIN_LOSS = 27
    const val MULLIGAN = 34
    const val STARTING_PLAYER = 37

    /** Legend rule "choose which to keep". */
    const val SELECT_N_LEGEND_RULE = 72

    const val GROUP_SCRY = 92
    const val GROUP_SURVEIL = 129
    const val SEARCH = 1065
    const val SELECT_N = 1243

    /** sourceId on SelectNReq for legend rule. */
    const val SELECT_N_LEGEND_RULE_SOURCE = 15168
}
