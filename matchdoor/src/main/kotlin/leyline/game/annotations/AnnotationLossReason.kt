package leyline.game.annotations

/**
 * Loss reason wire values for the [AnnotationBuilder.lossOfGame] `reason` detail.
 *
 * These are client annotation-specific encodings and do not match the proto
 * [wotc.mtgo.gre.external.messaging.Messages.ResultReason] enum — callers that
 * start from `ResultReason` need an explicit mapping.
 *
 * The annotation detail is mixed-type in the protocol: concession and life-total
 * losses use legacy numeric values, while poison and empty-library losses use
 * symbolic string reasons.
 */
enum class AnnotationLossReason(
    val wireInt: Int? = null,
    val wireString: String? = null,
) {
    LifeTotal(wireInt = 0),
    Concede(wireInt = 3),
    Poison(wireString = "SBA_Poison"),
    DrawFromEmptyLibrary(wireString = "SBA_DrawFromEmptyLib"),
}
