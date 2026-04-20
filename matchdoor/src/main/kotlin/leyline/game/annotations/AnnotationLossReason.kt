package leyline.game.annotations

/**
 * Loss reason wire values for the [AnnotationBuilder.lossOfGame] `reason` detail.
 *
 * These are client annotation-specific encodings and do not match the proto
 * [wotc.mtgo.gre.external.messaging.Messages.ResultReason] enum — callers that
 * start from `ResultReason` need an explicit mapping.
 */
enum class AnnotationLossReason(val wireValue: Int) {
    LifeTotal(0),
    Concede(3),
}
