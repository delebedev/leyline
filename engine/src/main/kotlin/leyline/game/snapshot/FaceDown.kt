package leyline.game.snapshot

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.zone.ZoneType

enum class FaceDownKind {
    Disguise,
    ManifestDread,
}

/**
 * Classifies supported face-down card states before identity is suppressed in
 * the client projection.
 *
 * Forge retains the originating ability on manifested cards, which separates
 * Manifest Dread from ordinary Manifest without relying on the hidden card's
 * name or printed characteristics.
 */
object FaceDown {
    fun kind(card: Card): FaceDownKind? =
        when {
            Disguise.isFaceDownDisguise(card) -> FaceDownKind.Disguise
            isManifestDread(card) -> FaceDownKind.ManifestDread
            else -> null
        }

    private fun isManifestDread(card: Card): Boolean =
        card.isFaceDown &&
            card.isManifested &&
            card.isInZone(ZoneType.Battlefield) &&
            card.manifestedSA?.api == ApiType.ManifestDread
}
