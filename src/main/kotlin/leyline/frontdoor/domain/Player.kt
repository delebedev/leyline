package leyline.frontdoor.domain

@JvmInline value class SessionId(val value: String)

data class Player(
    val id: PlayerId,
    val screenName: String,
)
