package leyline.game.mapping

/** Protocol prompt IDs matching expected protocol values. */
object PromptIds {
    const val PASS_PRIORITY = 2
    const val DECLARE_ATTACKERS = 6
    const val ORDER_BLOCKERS = 7
    const val ASSIGN_DAMAGE = 8
    const val SELECT_TARGETS = 10
    const val PAY_COSTS = 11
    const val CASTING_TIME_OPTIONS = 23
    const val MATCH_RESULT_WIN_LOSS = 27

    /** Post-game "reveal hand" option. */
    const val REVEAL_HAND = 29

    /** Post-game "draw card" option. */
    const val DRAW_CARD = 30
    const val MULLIGAN = 34
    const val STARTING_PLAYER = 37

    /** Legend rule "choose which to keep". */
    const val SELECT_N_LEGEND_RULE = 72

    const val GROUP_SCRY = 92
    const val GROUP_SURVEIL = 129
    const val SEARCH = 1065

    /**
     * Typecycling-shape library search — "Search your library for an X card,
     * reveal it, put it into your hand, then shuffle."
     *
     * Typecycling and basiccycling cards (cycling/swampcycling/islandcycling/
     * forestcycling/mountaincycling/plainscycling/basiccycling/wizardcycling/
     * slivercycling) all map to this promptId. The picker UI keys on it to
     * render the highlight-all-valid layout (every Type-matching library card
     * gets the blue glow + click-to-select-and-submit affordance).
     *
     * Generic searches (Diabolic Tutor, etc.) use [SEARCH] instead.
     */
    const val SEARCH_TYPECYCLING = 11626

    /** Mandatory additional cost (discard). Client expects PayCostsReq promptId=1024. */
    const val DISCARD_COST = 1024

    /** "You may" trigger decision (OptionalActionMessage). */
    const val OPTIONAL_ACTION = 1159

    /** Commander zone replacement decision: "Move your commander to the command zone?" */
    const val COMMANDER_RETURN_TO_COMMAND = 144

    /** Shock land ETB "pay life or enter tapped" (OptionalActionMessage). */
    const val SHOCK_LAND_ETB = 2233

    /** Endure trigger resolution "put +1/+1 counters or create a Spirit token" (OptionalActionMessage).
     *  Loc text: "Put N +1/+1 counters on this creature?" — Yes = counters, No = Spirit token. */
    const val ENDURE_PUT_COUNTERS = 13976

    const val SELECT_N = 1243

    /** Mutate target group — "Target a non-Human creature you own." */
    const val MUTATE_TARGET = 141

    /** Mentor target group — "target attacking creature with lesser power." */
    const val MENTOR_TARGET = 2247

    /**
     * Stock Up's outer-prompt loc key — "Put two of them into your hand."
     *
     * This value is Stock-Up-specific; other Dig-shape effects (Sleight of Hand,
     * Impulse, etc.) almost certainly need different loc keys. A card-specific
     * dispatcher keyed on `(sa.api == Dig, sa.hostCard.grpId, ChangeNum)` is
     * the right long-term home — today this constant is the only value we
     * have a confirmed-rendering integration for.
     */
    const val SELECT_N_STOCK_UP = 2490

    /** Inner SelectNReq.prompt PromptId Parameter value for look-and-pick prompts.
     *  The literal `2` is opaque (no dictionary entry) but is the value the
     *  client expects on this slot for resolution-time pick prompts. */
    const val SELECT_N_INNER_PARAMETER = 2

    const val CHOOSE_OR_COST = 1103
    const val CHOOSE_OR_COST_PAY_SACRIFICE = 1029
    const val CHOOSE_OR_COST_PAY_MANA = 4160

    /** Pay-cost-via-select for "exile N from graveyard" — Escape's additional cost. */
    const val CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE = 5500

    /** Enlist attack cost — "Tap a creature for {CardId} to enlist." */
    const val ENLIST_TAP_COST = 11225

    /** Station activation cost — "Tap a creature to add charge counters equal to its power." */
    const val STATION_TAP_COST = 14726

    /** Ninjutsu activation cost — "Return an unblocked attacking creature you control to its owner's hand." */
    const val NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST = 8580

    /** sourceId on SelectNReq for legend rule. */
    const val SELECT_N_LEGEND_RULE_SOURCE = 15168

    /** Numeric input — "Choose X" / pay X stepper. Single observed loc key for X-cost prompts. */
    const val NUMERIC_INPUT = 51
}
