package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Declarative table of card-state designation rails. Each row describes one
 * designation kind (Prepared, Plotted, Foretold) and the transient annotation
 * pair it emits on gain / lose. [StateMapper] iterates the table once per
 * Diff GSM, diffing prev vs cur snapshot membership on the row's
 * [CardStateDesignationSpec.readRole] predicate.
 *
 * Replaces the previous StateMapper.insertPreparedTransients /
 * insertPlottedTransients / insertForetellTransients triplet — adding a
 * designation (Saddled, Day/Night, Door states, …) is now one row plus the
 * BoundCard recognizer that populates [BoundCard.designations].
 *
 * The persistent-side Designation annotations (carried across GSMs) live in
 * [leyline.game.state.PersistentAnnotationStore] and are NOT part of this
 * table — per-kind persistent emit + identity rules live on
 * [leyline.game.state.PreparedDesignationKind] /
 * [leyline.game.state.PlottedDesignationKind] in the kind registry.
 */

enum class DesignationKind {
    PREPARED,
    PLOTTED,
    FORETOLD,
    LEFT_UNLOCKED,
    RIGHT_UNLOCKED,
}

/**
 * How transient gain / lose annotations land in the per-GSM annotation list.
 *  - [GAIN_INSERT_BEFORE_RESOLVE_ZT] — Prepared. Gain inserts immediately
 *    before the Stack→Battlefield Resolve `ZoneTransfer` for the same source
 *    iid (the protocol bracket fixes annotation 848 immediately before 849);
 *    lose appends.
 *  - [GAIN_APPEND] — Plotted. Gain and lose both append at end of list. The
 *    plot activation moves the card Hand→Exile (no Resolve ZT to anchor on).
 *  - [FACE_DOWN_PAIR] — Foretold. Gain emits FaceDown +
 *    SuppressedPowerAndToughness; no lose pair (face-down state comes off
 *    via the cast-acceptance ZT alone).
 */
enum class TransientMode {
    GAIN_INSERT_BEFORE_RESOLVE_ZT,
    GAIN_APPEND,
    FACE_DOWN_PAIR,
}

data class CardStateDesignationSpec(
    val kind: DesignationKind,
    /** `DesignationType` detail value. Null for [DesignationKind.FORETOLD] —
     *  FaceDown / SuppressedPowerAndToughness carry no designation-type field. */
    val designationType: Int?,
    val mode: TransientMode,
    val readRole: (BoundCard) -> Boolean,
)

object CardStateDesignations {
    val Prepared =
        CardStateDesignationSpec(
            kind = DesignationKind.PREPARED,
            designationType = AnnotationConstants.DESIGNATION_TYPE_PREPARED,
            mode = TransientMode.GAIN_INSERT_BEFORE_RESOLVE_ZT,
            readRole = { it.designations.isPreparedSource },
        )
    val Plotted =
        CardStateDesignationSpec(
            kind = DesignationKind.PLOTTED,
            designationType = AnnotationConstants.DESIGNATION_TYPE_PLOTTED,
            mode = TransientMode.GAIN_APPEND,
            readRole = { it.designations.isPlotted },
        )
    val Foretold =
        CardStateDesignationSpec(
            kind = DesignationKind.FORETOLD,
            designationType = null,
            mode = TransientMode.FACE_DOWN_PAIR,
            readRole = { it.designations.isForetold },
        )
    val LeftUnlocked =
        CardStateDesignationSpec(
            kind = DesignationKind.LEFT_UNLOCKED,
            designationType = AnnotationConstants.DESIGNATION_TYPE_LEFT_UNLOCKED,
            // Gain fires in the same GSM as the Stack→Battlefield Resolve ZT
            // for the room — the door's unlock effect runs at resolution.
            mode = TransientMode.GAIN_INSERT_BEFORE_RESOLVE_ZT,
            readRole = { it.designations.isLeftDoorUnlocked },
        )
    val RightUnlocked =
        CardStateDesignationSpec(
            kind = DesignationKind.RIGHT_UNLOCKED,
            designationType = AnnotationConstants.DESIGNATION_TYPE_RIGHT_UNLOCKED,
            mode = TransientMode.GAIN_INSERT_BEFORE_RESOLVE_ZT,
            readRole = { it.designations.isRightDoorUnlocked },
        )

    val all: List<CardStateDesignationSpec> = listOf(Prepared, Plotted, Foretold, LeftUnlocked, RightUnlocked)
}

/**
 * Diff prev vs cur over each row's [CardStateDesignationSpec.readRole]; emit
 * transient gain / lose annotations into [annotations] per the row's mode.
 *
 * Caller must have already verified `prev != null` — full-snapshot rebuild
 * skips transients (the persistent Designation pAnn alone re-syncs client
 * state on rebuild; transients are for *changes*).
 */
internal fun insertStateDesignationTransients(
    annotations: MutableList<AnnotationInfo>,
    prev: GsmSnapshot,
    cur: GsmSnapshot,
    resolveInstanceId: (ForgeCardId) -> InstanceId,
) {
    for (spec in CardStateDesignations.all) {
        val curIds = forgeIdsByRole(cur, spec.readRole)
        val prevIds = forgeIdsByRole(prev, spec.readRole)
        for (fid in curIds - prevIds) emitGain(annotations, spec, resolveInstanceId(fid))
        // FACE_DOWN_PAIR has no lose path — skip the prev-set scan entirely
        // rather than iterate just to call a no-op emitter.
        if (spec.mode == TransientMode.FACE_DOWN_PAIR) continue
        for (fid in prevIds - curIds) emitLose(annotations, spec, resolveInstanceId(fid))
    }
}

private fun forgeIdsByRole(
    snap: GsmSnapshot,
    role: (BoundCard) -> Boolean,
): Set<ForgeCardId> =
    snap.boundCards.values
        .filter(role)
        .map { it.forgeCardId }
        .toSet()

private fun emitGain(
    annotations: MutableList<AnnotationInfo>,
    spec: CardStateDesignationSpec,
    iid: InstanceId,
) {
    when (spec.mode) {
        TransientMode.GAIN_INSERT_BEFORE_RESOLVE_ZT -> {
            val gain =
                AnnotationBuilder.gainDesignationOnCard(
                    instanceId = iid,
                    designationType = requireDesignationType(spec, "GAIN_INSERT_BEFORE_RESOLVE_ZT"),
                )
            insertGainBeforeResolveZt(annotations, gain, iid.value)
        }
        TransientMode.GAIN_APPEND -> {
            annotations.add(
                AnnotationBuilder.gainDesignationOnCard(
                    instanceId = iid,
                    designationType = requireDesignationType(spec, "GAIN_APPEND"),
                ),
            )
        }
        TransientMode.FACE_DOWN_PAIR -> {
            annotations.add(AnnotationBuilder.faceDown(iid))
            annotations.add(AnnotationBuilder.suppressedPowerAndToughness(iid))
        }
    }
}

private fun emitLose(
    annotations: MutableList<AnnotationInfo>,
    spec: CardStateDesignationSpec,
    iid: InstanceId,
) {
    when (spec.mode) {
        TransientMode.GAIN_INSERT_BEFORE_RESOLVE_ZT,
        TransientMode.GAIN_APPEND,
        -> {
            annotations.add(
                AnnotationBuilder.loseDesignation(
                    instanceId = iid,
                    designationType = requireDesignationType(spec, "lose path"),
                ),
            )
        }
        TransientMode.FACE_DOWN_PAIR -> {
            // No lose pair — face-down state comes off via the cast-acceptance ZT alone.
        }
    }
}

private fun requireDesignationType(
    spec: CardStateDesignationSpec,
    context: String,
): Int =
    spec.designationType
        ?: error("$context requires a designationType — kind=${spec.kind} has none configured")

/**
 * Insert [gain] right before the Stack→Battlefield Resolve `ZoneTransfer`
 * whose `affectedIds` includes [sourceIid]. Falls back to appending if no
 * matching ZT is in [annotations] — the GSM still carries the persistent
 * Designation pAnn, so the gain transient at end-of-list is a degraded but
 * non-broken position.
 */
private fun insertGainBeforeResolveZt(
    annotations: MutableList<AnnotationInfo>,
    gain: AnnotationInfo,
    sourceIid: Int,
) {
    val ztIndex =
        annotations.indexOfFirst { ann ->
            ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                ann.affectedIdsList.contains(sourceIid) &&
                ann.detailsList.any {
                    it.key == DetailKeys.CATEGORY &&
                        it.valueStringCount > 0 &&
                        it.getValueString(0) == "Resolve"
                }
        }
    if (ztIndex >= 0) annotations.add(ztIndex, gain) else annotations.add(gain)
}
