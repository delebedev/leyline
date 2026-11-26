package leyline.game.snapshot

import forge.card.CardStateName
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.zone.ZoneType

enum class FaceDownKind {
    Cloak,
    Disguise,
    ManifestDread,
    Morph,
}

/**
 * Classifies mechanic-specific face-down metadata. Identity concealment follows
 * actual face-down state even when no mechanic identity is recognized here.
 *
 * Forge retains the originating ability on manifested cards, which separates
 * Manifest Dread from ordinary Manifest without relying on the hidden card's
 * name or printed characteristics.
 */
object FaceDown {
    fun kind(card: Card): FaceDownKind? =
        when {
            isCloak(card) -> FaceDownKind.Cloak
            Disguise.isFaceDownDisguise(card) -> FaceDownKind.Disguise
            isManifestDread(card) -> FaceDownKind.ManifestDread
            isMorph(card) -> FaceDownKind.Morph
            else -> null
        }

    private fun isCloak(card: Card): Boolean =
        card.isFaceDown &&
            card.isCloaked &&
            card.isInZone(ZoneType.Battlefield)

    /**
     * A card cast face down for morph or megamorph. Forge's face-down state
     * drops the printed keywords, so the test reads the original state.
     */
    private fun isMorph(card: Card): Boolean {
        if (!card.isFaceDown || card.isManifested || card.isCloaked) return false
        if (!card.isInZone(ZoneType.Battlefield) && !card.isInZone(ZoneType.Stack)) return false
        val original = card.getOriginalState(CardStateName.Original) ?: return false
        return original.intrinsicKeywords.any { it.original.startsWith("Morph") || it.original.startsWith("Megamorph") }
    }

    private fun isManifestDread(card: Card): Boolean =
        card.isFaceDown &&
            card.isManifested &&
            card.isInZone(ZoneType.Battlefield) &&
            card.manifestedSA?.api == ApiType.ManifestDread
}
