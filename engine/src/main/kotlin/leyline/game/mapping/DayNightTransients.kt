package leyline.game.mapping

import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.TransferCategory
import leyline.game.codes.DetailKeys
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * State-tail diff for the game-scope Day/Night state primitive. Compares
 * `prev.dayTime` to `cur.dayTime` and emits the matching transient
 * `GainDesignation` / `LoseDesignation` lite-shape annotations on the game.
 *
 * Three transitions:
 *  - **neither → Day** (cur=false): one `GainDesignation{Day}`. Pre-state has
 *    no Day/Night designation, so no paired lose.
 *  - **neither → Night** (cur=true): one `GainDesignation{Night}`. Symmetric
 *    to the Day case — covers "becomes night" effects out of the gate
 *    (CR 731.1).
 *  - **Day ↔ Night flip** (prev=false→cur=true or true→false): outgoing
 *    state's `LoseDesignation` paired with incoming state's `GainDesignation`.
 *
 * The persistent `Designation` re-emission lives on the snapshot path —
 * `dayNightDesignationPersistentFromSnap` in [StateMapper]
 * does its own thing there, so this function only handles the transient
 * change-edge annotations. Initial Day precedes entry triggers from a resolving
 * permanent. Other designation changes follow the existing lifecycle events.
 *
 * No-op when prev.dayTime == cur.dayTime — the persistent re-emit covers
 * APSC ticks without us touching transient annotations.
 */
internal fun insertDayNightDesignationTransients(
    annotations: MutableList<AnnotationInfo>,
    prevDayTime: Boolean?,
    curDayTime: Boolean?,
) {
    if (prevDayTime == curDayTime) return
    if (prevDayTime != null) {
        annotations.add(
            AnnotationBuilder.loseDesignationOnGame(
                designationType = designationTypeOf(prevDayTime),
            ),
        )
    }
    if (curDayTime != null) {
        val gain = AnnotationBuilder.gainDesignationOnGame(designationType = designationTypeOf(curDayTime))
        val entryTriggerIndex =
            if (prevDayTime == null && !curDayTime) {
                val resolvedPermanents = mutableSetOf<Int>()
                annotations.indexOfFirst { annotation ->
                    if (AnnotationType.ZoneTransfer_af5a in annotation.typeList &&
                        annotation.detailsList.any {
                            it.key == DetailKeys.CATEGORY &&
                                it.valueStringList == listOf(TransferCategory.Resolve.label)
                        } &&
                        annotation.detailsList.any { it.key == DetailKeys.ZONE_DEST && it.valueInt32List == listOf(ZoneIds.BATTLEFIELD) }
                    ) {
                        resolvedPermanents.addAll(annotation.affectedIdsList)
                    }
                    AnnotationType.AbilityInstanceCreated in annotation.typeList && annotation.affectorId in resolvedPermanents
                }
            } else {
                -1
            }
        annotations.add(entryTriggerIndex.takeIf { it >= 0 } ?: annotations.size, gain)
    }
}

private fun designationTypeOf(isNight: Boolean): Int =
    if (isNight) {
        AnnotationConstants.DESIGNATION_TYPE_NIGHT
    } else {
        AnnotationConstants.DESIGNATION_TYPE_DAY
    }
