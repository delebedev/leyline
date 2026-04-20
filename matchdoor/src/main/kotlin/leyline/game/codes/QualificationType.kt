package leyline.game.codes

/**
 * Wire values for the `QualificationType` detail key on the client `Qualification`
 * annotation. The client has no dedicated proto enum for this field — the
 * values below are the ones we've observed in use.
 */
enum class QualificationType(
    val wireValue: Int,
) {
    /** Combat keyword qualification (e.g. Menace). */
    CombatKeyword(40),

    /** Adventure-cast eligibility qualification. */
    Adventure(47),
}
