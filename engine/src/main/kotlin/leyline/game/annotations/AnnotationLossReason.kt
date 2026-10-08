package leyline.game.annotations

/**
 * Loss reason wire values for the [AnnotationBuilder.lossOfGame] `reason` detail.
 *
 * These are client annotation-specific encodings and do not match the game
 * result enum; callers that start from a result enum need an explicit mapping.
 *
 * The annotation detail is mixed-type in the protocol: concession uses a numeric value, while state-based losses use symbolic strings.
 */
enum class AnnotationLossReason(
    val wireInt: Int? = null,
    val wireString: String? = null,
) {
    /** Compatibility code for causes without a supported symbolic mapping. */
    Unspecified(wireInt = 0),
    LifeTotal(wireString = "SBA_LifeTotal"),
    Concede(wireInt = 3),
    Poison(wireString = "SBA_Poison"),
    DrawFromEmptyLibrary(wireString = "SBA_DrawFromEmptyLib"),
}
