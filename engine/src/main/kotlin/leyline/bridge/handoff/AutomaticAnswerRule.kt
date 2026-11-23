package leyline.bridge.handoff

/**
 * A decision the bridge answers without asking the player. Every automatic answer
 * names one rule; anything else is asked. [reason] states why no player choice is lost.
 */
enum class AutomaticAnswerRule(
    val reason: String,
) {
    /**
     * Forge's "Do you want to play X?" and Discover's cast-or-hand confirmations, which
     * Forge follows with `playSaFromPlayEffect`.
     */
    PlayEffectPrecedesOptionalCast(
        "playSaFromPlayEffect asks whether to cast immediately afterwards; asking here would repeat that question",
    ),

    /** Forge's "Cancel Search?" confirmation after the search returned fewer cards than allowed. */
    SearchSelectionSubmitted(
        "the player already submitted the search selection with fewer cards; ending the search keeps that selection",
    ),

    /** A confirmation for a cost part of the spell or ability being cast or activated. */
    CommittedCastCost(
        "the player chose this cast or activation and its costs before payment; declining would abandon a committed action",
    ),

    /** Calling heads or tails before Forge flips a coin. */
    CoinFlipCall(
        "a fair flip is won with the same chance whichever side is called; the flip and its result are shown",
    ),

    /**
     * A choice where the shown options leave nothing to choose: at most one can be
     * shown, or the required count takes every shown option.
     */
    NoChoiceAmongShownOptions(
        "the shown options leave nothing to choose; section headers, hidden cards and unmapped values cannot be told apart or chosen",
    ),

    /** A confirmation inside the free cast of a Paradigm copy. */
    ParadigmCopyCast(
        "the copy is cast through its own cast presentation; this confirmation is part of that cast",
    ),
}
