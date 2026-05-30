package leyline.config

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeDecks(
    val seat1Deck: String? = null,
    val seat2Deck: String? = null,
)
