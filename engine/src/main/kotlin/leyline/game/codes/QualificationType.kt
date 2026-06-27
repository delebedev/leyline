package leyline.game.codes

/**
 * Wire values for the `QualificationType` detail key on the client `Qualification`
 * annotation. The client has no dedicated proto enum for this field — the
 * values below are the ones we've observed in use.
 */
enum class QualificationType(
    val wireValue: Int,
) {
    /** Static restriction: affected creature can't attack. */
    CantAttack(30),

    /** Static restriction: affected creature can't block, or can't block listed attackers. */
    CantBlock(31),

    /** Static evasion: affected creature can't be blocked, or can't be blocked by listed blockers. */
    CantBeBlocked(32),

    /** Combat keyword qualification (e.g. Menace). */
    CombatKeyword(40),

    /** Adventure-cast eligibility qualification. */
    Adventure(47),
}
