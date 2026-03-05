package leyline.frontdoor.domain

@JvmInline value class SessionId(val value: String)

data class Player(
    val id: PlayerId,
    val screenName: String,
)

data class MatchInfo(val matchId: String, val host: String, val port: Int)
