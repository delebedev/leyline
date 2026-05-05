package leyline.game.mapping

import forge.game.spellability.SpellAbility

/**
 * Discriminator for `SearchReq` picker layout selection — different
 * `promptId` values drive different client picker UIs, and the
 * discriminator is purely SA-shape, not card-text.
 */
object SearchShape {
    /**
     * True when the SA is a typecycling/landcycling/basiccycling-shape
     * library search — `AB$ ChangeZone | Origin$ Library | Destination$
     * Hand | ChangeType$ <type>` with the type narrower than `Card`.
     *
     * Picker layout: highlight every valid candidate face-up, click-to-pick
     * (no separate Submit). Driven by `PromptIds.SEARCH_TYPECYCLING`.
     *
     * Generic library tutors (Diabolic Tutor, Sylvan Ranger) — wider
     * `ChangeType` or no type filter — fall through to `PromptIds.SEARCH`.
     */
    fun isTypeCycling(sa: SpellAbility?): Boolean {
        if (sa == null) return false
        if (!sa.hasParam("Origin") || sa.getParam("Origin") != "Library") return false
        if (!sa.hasParam("Destination") || sa.getParam("Destination") != "Hand") return false
        if (!sa.hasParam("ChangeType")) return false
        return sa.getParam("ChangeType") != "Card"
    }
}
