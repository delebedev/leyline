package leyline.domain

/**
 * Player identities the server owns rather than a person, so content can be
 * addressed by who holds it. The public spectator feed reads its rotation from
 * [SPECTATOR], which keeps it independent of any real account.
 */
object SystemPlayers {
    val SPECTATOR = PlayerId("00000000-0000-4000-8000-000000000001")
}
