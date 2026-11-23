package leyline.config

import forge.game.keyword.Keyword

/** Host-owned coverage of effective battlefield keyword rows, separate from printed metadata. */
enum class KeywordProjection(
    val coverage: List<String>,
) {
    Native(emptyList()),

    /** Keyword rows follow Forge's current keyword membership, so lost and restored keywords match the live card. */
    WebEffectiveKeywords(Keyword.getAllKeywords().map(Keyword::toString)),
}
